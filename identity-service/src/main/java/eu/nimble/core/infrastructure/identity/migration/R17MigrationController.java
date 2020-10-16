package eu.nimble.core.infrastructure.identity.migration;

import eu.nimble.core.infrastructure.identity.entity.NegotiationSettings;
import eu.nimble.core.infrastructure.identity.entity.UaaUser;
import eu.nimble.core.infrastructure.identity.entity.UserInvitation;
import eu.nimble.core.infrastructure.identity.repository.NegotiationSettingsRepository;
import eu.nimble.core.infrastructure.identity.repository.PersonRepository;
import eu.nimble.core.infrastructure.identity.repository.UaaUserRepository;
import eu.nimble.core.infrastructure.identity.repository.UserInvitationRepository;
import eu.nimble.core.infrastructure.identity.service.IdentityService;
import eu.nimble.core.infrastructure.identity.service.RocketChatService;
import eu.nimble.core.infrastructure.identity.system.dto.rocketchat.list.ChatUser;
import eu.nimble.core.infrastructure.identity.system.dto.rocketchat.list.ChatUsers;
import eu.nimble.core.infrastructure.identity.system.dto.rocketchat.list.UserEmail;
import eu.nimble.core.infrastructure.identity.uaa.KeycloakAdmin;
import eu.nimble.service.model.ubl.commonaggregatecomponents.*;
import eu.nimble.service.model.ubl.commonbasiccomponents.CodeType;
import eu.nimble.service.model.ubl.commonbasiccomponents.QuantityType;
import eu.nimble.service.model.ubl.commonbasiccomponents.TextType;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static eu.nimble.core.infrastructure.identity.uaa.OAuthClient.Role.PLATFORM_MANAGER;

@Controller
public class R17MigrationController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private IdentityService identityService;
    @Autowired
    private UaaUserRepository uaaUserRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private KeycloakAdmin keycloakAdmin;
    @Autowired
    private UserInvitationRepository userInvitationRepository;
    @Autowired
    private RocketChatService chatService;
    @Autowired
    private NegotiationSettingsRepository negotiationSettingsRepository;

    private final String oldIncoterm = "DAT (Delivered at Terminal)";
    private final String newIncoterm = "DPU (Delivery at Place Unloaded)";

    @ApiOperation(value = "", notes = "Validates the data of users")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Completed the validation successfully"),
            @ApiResponse(code = 401, message = "Invalid role")
    })
    @RequestMapping(value = "/r17/migration/validate-data",
            produces = {"application/json"},
            method = RequestMethod.PATCH)
    public ResponseEntity validateData(@ApiParam(value = "The Bearer token provided by the identity service", required = true) @RequestHeader(value = "Authorization") String bearerToken
    ) throws IOException {
        logger.info("Incoming request to validate data");

        // validate role
        if (!identityService.hasAnyRole(bearerToken, PLATFORM_MANAGER))
            return new ResponseEntity<>("Only platform managers are allowed to run this migration script", HttpStatus.FORBIDDEN);

        logger.info("Validating UaaUser");
        List<UaaUser> uaaUsers = (List<UaaUser>) uaaUserRepository.findAll();
        for (UaaUser uaaUser : uaaUsers) {
            try {
                keycloakAdmin.getUserResource(uaaUser.getExternalID()).toRepresentation();
            } catch (NotFoundException exception) {
                logger.error("{} does not exist on Keycloak", uaaUser.getUsername());
                uaaUserRepository.delete(uaaUser);
            }
        }

        logger.info("Validating Person");
        List<PersonType> personTypes = (List<PersonType>) personRepository.findAll();
        for (PersonType personType : personTypes) {
            String email = personType.getContact().getElectronicMail();
            if (email != null) {
                UaaUser uaaUser = uaaUserRepository.findOneByUsername(email);
                if (uaaUser == null) {
                    logger.error("UaaUser does not exist for person:{}", email);
                    personRepository.delete(personType);
                }
            }
        }

        logger.info("Validating User Invitations");
        List<UserInvitation> userInvitations = (List<UserInvitation>) userInvitationRepository.findAll();
        for (UserInvitation userInvitation : userInvitations) {
            if (!userInvitation.getPending()) {
                UaaUser uaaUser = uaaUserRepository.findOneByUsername(userInvitation.getEmail());
                if (uaaUser == null) {
                    logger.error("UaaUser does not exist for user:{}", userInvitation.getEmail());
                    userInvitationRepository.delete(userInvitation);
                }
            }
        }

        if (chatService.isChatEnabled()) {
            logger.info("Validating chat service users");
            ChatUsers chatUsers = chatService.listUsers();
            List<ChatUser> chatUserList = chatUsers.getUsers();
            for (ChatUser chatUser : chatUserList) {
                if (chatUser.getEmails() != null) {
                    boolean userExists = false;
                    String emailAddress = null;
                    for (UserEmail userEmail : chatUser.getEmails()) {
                        emailAddress = userEmail.getAddress();
                        UaaUser uaaUser = uaaUserRepository.findOneByUsername(emailAddress);
                        if (uaaUser != null) {
                            userExists = true;
                            break;
                        }
                    }
                    if (!userExists && emailAddress != null) {
                        logger.error("UaaUser does not exist for user:{}", emailAddress);
                        chatService.deleteUser(emailAddress);
                    }
                }
            }
        }

        logger.info("Completed request to validate data");
        return ResponseEntity.ok(null);
    }

    @ApiOperation(value = "", notes = "Add titles to each clause")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Add titles to each clause successfully"),
            @ApiResponse(code = 401, message = "Invalid role"),
            @ApiResponse(code = 500, message = "Unexpected error while adding title to the clause")
    })
    @RequestMapping(value = "/r17/migration/clause-title",
            produces = {"application/json"},
            method = RequestMethod.PATCH)
    public ResponseEntity addClauseTitles(@ApiParam(value = "The Bearer token provided by the identity service", required = true) @RequestHeader(value = "Authorization", required = true) String bearerToken
    ) throws IOException {
        logger.info("Incoming request to add clause titles");

        // validate role
        if (!identityService.hasAnyRole(bearerToken, PLATFORM_MANAGER))
            return new ResponseEntity<>("Only platform managers are allowed to run this migration script", HttpStatus.FORBIDDEN);

        List<NegotiationSettings> negotiationSettings = (List<NegotiationSettings>) negotiationSettingsRepository.findAll();
        for (NegotiationSettings negotiationSetting : negotiationSettings) {
            if(negotiationSetting.getCompany().getSalesTerms() != null){
                List<ClauseType> clauseTypes = negotiationSetting.getCompany().getSalesTerms().getTermOrCondition();
                for (ClauseType clause : clauseTypes) {
                    String clauseId = clause.getID();
                    String clauseTitle = clauseId.substring(clauseId.indexOf("_")+1);
                    TextType title = new TextType();
                    title.setLanguageID("en");
                    title.setValue(clauseTitle);
                    clause.getClauseTitle().add(title);
                }
            negotiationSettingsRepository.save(negotiationSetting);
            }
        }

        logger.info("Completed request to add clause titles");
        return ResponseEntity.ok(null);
    }

    @ApiOperation(value = "", notes = "Updates incoterms")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Updated the incoterms successfully"),
            @ApiResponse(code = 401, message = "Invalid role")
    })
    @RequestMapping(value = "/r17/migration/incoterms",
            produces = {"application/json"},
            method = RequestMethod.PATCH)
    public ResponseEntity updateIncoterms(@ApiParam(value = "The Bearer token provided by the identity service", required = true) @RequestHeader(value = "Authorization") String bearerToken
    ) throws IOException {
        logger.info("Incoming request to update incoterms");

        // validate role
        if (!identityService.hasAnyRole(bearerToken, PLATFORM_MANAGER))
            return new ResponseEntity<>("Only platform managers are allowed to run this migration script", HttpStatus.FORBIDDEN);

        List<NegotiationSettings> negotiationSettingsList = (List<NegotiationSettings>) negotiationSettingsRepository.findAll();
        for (NegotiationSettings negotiationSettings : negotiationSettingsList) {
            boolean saveNegotiationSettings = false;
            if(negotiationSettings.getIncoterms().contains(oldIncoterm)){
                Collections.replaceAll(negotiationSettings.getIncoterms(),oldIncoterm,newIncoterm);
                saveNegotiationSettings = true;
            }

            PartyType partyType = negotiationSettings.getCompany();
            if(partyType.getSalesTerms() != null){
                List<ClauseType> clauseTypes = negotiationSettings.getCompany().getSalesTerms().getTermOrCondition();
                for (ClauseType clauseType : clauseTypes) {
                    List<TradingTermType> tradingTermTypesIncludingIncoterms = clauseType.getTradingTerms().stream().filter(tradingTermType -> tradingTermType.getValue().getValueQualifier().contentEquals("CODE") && tradingTermType.getValue().getValueCode().get(0).getListID().contentEquals("INCOTERMS_LIST")).collect(Collectors.toList());
                    for (TradingTermType tradingTerm : tradingTermTypesIncludingIncoterms) {
                        for (CodeType codeType : tradingTerm.getValue().getValueCode()) {
                            if(codeType.getValue().contentEquals(oldIncoterm)){
                                codeType.setValue(newIncoterm);
                                saveNegotiationSettings = true;
                            }
                        }
                    }
                }
            }

            if(saveNegotiationSettings){
                negotiationSettingsRepository.save(negotiationSettings);
            }
        }

        logger.info("Completed request to update incoterms");
        return ResponseEntity.ok(null);
    }

    @ApiOperation(value = "", notes = "Replaces 'day(s)' unit with 'calendar day(s)' unit for time quantities")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Updated the time quantity units successfully"),
            @ApiResponse(code = 401, message = "Invalid role")
    })
    @RequestMapping(value = "/r17/migration/time-units",
            produces = {"application/json"},
            method = RequestMethod.PATCH)
    public ResponseEntity updateTimeQuantityUnits(@ApiParam(value = "The Bearer token provided by the identity service", required = true) @RequestHeader(value = "Authorization") String bearerToken
    ) throws IOException {
        logger.info("Incoming request to update time quantity units");

        // validate role
        if (!identityService.hasAnyRole(bearerToken, PLATFORM_MANAGER))
            return new ResponseEntity<>("Only platform managers are allowed to run this migration script", HttpStatus.FORBIDDEN);


        List<NegotiationSettings> negotiationSettingsList = (List<NegotiationSettings>) negotiationSettingsRepository.findAll();
        for (NegotiationSettings negotiationSettings : negotiationSettingsList) {
            boolean saveNegotiationSettings = false;

            if(negotiationSettings.getDeliveryPeriodUnits().contains("day(s)")){
                Collections.replaceAll(negotiationSettings.getDeliveryPeriodUnits(),"day(s)","calendar day(s)");
                saveNegotiationSettings = true;
            }

            PartyType partyType = negotiationSettings.getCompany();
            if(partyType.getSalesTerms() != null){
                List<ClauseType> clauseTypes = negotiationSettings.getCompany().getSalesTerms().getTermOrCondition();
                for (ClauseType clauseType : clauseTypes) {
                    for (TradingTermType tradingTerm : clauseType.getTradingTerms()) {
                        if(tradingTerm.getValue().getValueQualifier().contentEquals("QUANTITY")){
                            for (QuantityType quantityType : tradingTerm.getValue().getValueQuantity()) {
                                if(quantityType.getUnitCode() != null && quantityType.getUnitCode().contentEquals("day(s)")){
                                    quantityType.setUnitCode("calendar day(s)");
                                    saveNegotiationSettings = true;
                                }
                            }
                        }
                    }
                }
            }

            if(saveNegotiationSettings){
                negotiationSettingsRepository.save(negotiationSettings);
            }
        }

        logger.info("Completed request to update time quantity units");
        return ResponseEntity.ok(null);
    }
}