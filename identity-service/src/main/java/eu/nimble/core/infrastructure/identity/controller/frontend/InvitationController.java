package eu.nimble.core.infrastructure.identity.controller.frontend;

import eu.nimble.core.infrastructure.identity.controller.IdentityUtils;
import eu.nimble.core.infrastructure.identity.entity.UaaUser;
import eu.nimble.core.infrastructure.identity.entity.UserInvitation;
import eu.nimble.core.infrastructure.identity.mail.EmailService;
import eu.nimble.core.infrastructure.identity.repository.*;
import eu.nimble.core.infrastructure.identity.uaa.KeycloakAdmin;
import eu.nimble.core.infrastructure.identity.uaa.OAuthClient;
import eu.nimble.core.infrastructure.identity.uaa.OpenIdConnectUserDetails;
import eu.nimble.service.model.ubl.commonaggregatecomponents.PartyType;
import eu.nimble.service.model.ubl.commonaggregatecomponents.PersonType;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;

@Controller
public class InvitationController {

    private static final Logger logger = LoggerFactory.getLogger(InvitationController.class);

    @Autowired
    private PartyRepository partyRepository;

    @Autowired
    private UaaUserRepository uaaUserRepository;

    @Autowired
    private UserInvitationRepository userInvitationRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private IdentityUtils identityUtils;

    @Autowired
    private KeycloakAdmin keycloakAdmin;

    @ApiOperation(value = "", notes = "Send invitation to user.", response = ResponseEntity.class, tags = {})
    @RequestMapping(value = "/send_invitation", produces = {"application/json"}, method = RequestMethod.POST)
    ResponseEntity<?> sendInvitation(
            @ApiParam(value = "Invitation object.", required = true) @Valid @RequestBody UserInvitation invitation,
            @RequestHeader(value = "Authorization") String bearer,
            HttpServletRequest request) throws IOException {

        OpenIdConnectUserDetails userDetails = OpenIdConnectUserDetails.fromBearer(bearer);
        if (identityUtils.hasRole(bearer, OAuthClient.Role.LEGAL_REPRESENTATIVE) == false)
            return new ResponseEntity<>("Only legal representatives are allowed to invite users", HttpStatus.UNAUTHORIZED);

        String emailInvitee = invitation.getEmail();
        String companyId = invitation.getCompanyId();

        // obtain sending company and user
        Optional<PartyType> parties = partyRepository.findByHjid(Long.parseLong(companyId)).stream().findFirst();
        if (parties.isPresent() == false) {
            logger.info("Invitation: Requested party with Id {} not found", companyId);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        PartyType company = parties.get();

        // collect information of sending user
        UaaUser sender = uaaUserRepository.findByExternalID(userDetails.getUserId());
        PersonType sendingPerson = sender.getUBLPerson();
        String senderName = sendingPerson.getFirstName() + " " + sendingPerson.getFamilyName();

        // check if user has already been invited
        if (userInvitationRepository.findByEmail(emailInvitee).isEmpty() == false) {
            logger.info("Invitation: Impossible to register user {} twice for company {}.", emailInvitee, companyId);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        // saving invitation
        List<String> userRoleIDs = invitation.getRoleIDs() == null ? new ArrayList() : invitation.getRoleIDs();
        UserInvitation userInvitation = new UserInvitation(emailInvitee, companyId, userRoleIDs, sender);
        userInvitationRepository.save(userInvitation);

        List<String> prettifedRoles = KeycloakAdmin.prettfiyRoleIDs(userRoleIDs);

        // check if user is already registered
        Optional<UaaUser> potentialInvitee = uaaUserRepository.findByUsername(emailInvitee).stream().findFirst();
        if (potentialInvitee.isPresent()) {

            UaaUser invitee = potentialInvitee.get();

            // check if user is already part of a company
            List<PartyType> companiesOfInvitee = partyRepository.findByPerson(invitee.getUBLPerson());
            if (companiesOfInvitee.isEmpty() == false) {
                logger.info("Invitation: User {} is already member of another company.", emailInvitee);
                return new ResponseEntity<>(HttpStatus.CONFLICT);
            }

            // ToDo: let user accept invitation

            // send information
            emailService.informInviteExistingCompany(emailInvitee, senderName, company.getName(), prettifedRoles);
            logger.info("Invitation: User {} is already on the platform (without company). Invite from {} ({}) sent.",
                    emailInvitee, sender.getUsername(), company.getName());

            // add existing user to company
            company.getPerson().add(potentialInvitee.get().getUBLPerson());

            // set request roles to user
            keycloakAdmin.applyRoles(invitee.getExternalID(), new HashSet<>(userRoleIDs));

            // update invitation
            userInvitation.setPending(false);
            userInvitationRepository.save(userInvitation);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Existing user added to company");
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        }

        // send invitation
        emailService.sendInvite(emailInvitee, senderName, company.getName(), prettifedRoles);

        logger.info("Invitation sent FROM {} ({}, {}) TO {}", senderName, company.getName(), companyId, emailInvitee);

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation(value = "", notes = "Get pending invitations.", response = UserInvitation.class, responseContainer = "List", tags = {})
    @RequestMapping(value = "/invitations", produces = {"application/json"}, method = RequestMethod.GET)
    ResponseEntity<?> pendingInvitations(@RequestHeader(value = "Authorization") String bearer) throws IOException {
        UaaUser user = identityUtils.getUserfromBearer(bearer);

        Optional<PartyType> companyOpt = identityUtils.getCompanyOfUser(user);
        if (companyOpt.isPresent() == false) {
            logger.info("Pending Invitations: Requested party for user {} not found.", user.getUsername());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        PartyType company = companyOpt.get();

        List<UserInvitation> pendingInvitations = userInvitationRepository.findByCompanyId(company.getID());

        // update roles
        for (UserInvitation invitation : pendingInvitations) {
            if (invitation.getPending() == false) {
                String username = invitation.getEmail();
                UaaUser uaaUser = uaaUserRepository.findOneByUsername(username);
                if (uaaUser != null) {
                    Set<String> roles = keycloakAdmin.getUserRoles(uaaUser.getExternalID());
                    invitation.setRoleIDs(new ArrayList<>(roles));
                }
            }
        }

        return new ResponseEntity<>(pendingInvitations, HttpStatus.OK);
    }


    @ApiOperation(value = "", notes = "Remove existing invitation.", response = String.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "User removed from company", response = String.class),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 409, message = "User not in company")})
    @RequestMapping(value = "/invitations", method = RequestMethod.DELETE)
    ResponseEntity<?> removeInvitation(@ApiParam(value = "Username", required = true) @RequestParam String username,
                                       @RequestHeader(value = "Authorization") String bearer) throws IOException {

        // check if authorized
        if (identityUtils.hasRole(bearer, OAuthClient.Role.LEGAL_REPRESENTATIVE) == false)
            return new ResponseEntity<>("Only legal representatives are allowed to invite users", HttpStatus.UNAUTHORIZED);

        logger.info("Requesting removal of company membership of user {}.", username);

        // delete invitation
        List<UserInvitation> deletedInvitations = userInvitationRepository.removeByEmail(username);
        String responseMessage = deletedInvitations.isEmpty() ? "" : "Removed invitation";

        if (deletedInvitations.isEmpty() == false)
            logger.info("Removed invitation of user {}.", username);

        // remove person from company
        UaaUser userToRemove = uaaUserRepository.findOneByUsername(username);
        UaaUser requester = uaaUserRepository.findOneByUsername(identityUtils.getUserDetails(bearer).getUsername());
        if (userToRemove != null && requester != null &&
                userToRemove.getUsername().equals(requester.getUsername()) == false) {  // user cannot remove itself
            if (identityUtils.inSameCompany(userToRemove, requester) == false)
                return new ResponseEntity<>(HttpStatus.CONFLICT);


            // remove from list of persons
            Optional<PartyType> companyOpt = identityUtils.getCompanyOfUser(requester);
            if (companyOpt.isPresent()) {

                // remove roles of user
                keycloakAdmin.applyRoles(userToRemove.getExternalID(), Collections.emptySet());

                PartyType company = companyOpt.get();
                company.getPerson().remove(userToRemove.getUBLPerson());
                partyRepository.save(company);
                responseMessage += "\nRemoved from company";

                logger.info(requester.getUsername() + " removed " + userToRemove.getUsername() + " from company " + company.getName());
            }
        }

        responseMessage = responseMessage.isEmpty() ? "No changes" : responseMessage;
        return new ResponseEntity<>(responseMessage, HttpStatus.OK);
    }
}