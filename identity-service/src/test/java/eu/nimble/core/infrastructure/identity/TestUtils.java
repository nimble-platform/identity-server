package eu.nimble.core.infrastructure.identity;

import eu.nimble.core.infrastructure.identity.config.NimbleConfigurationProperties;
import eu.nimble.core.infrastructure.identity.entity.dto.*;
import eu.nimble.service.model.ubl.commonaggregatecomponents.PersonType;

import java.util.Collections;

/**
 * Created by Johannes Innerbichler on 2019-02-01.
 */
public class TestUtils {

    public static final String JSON_DATE_FORMAT = "yyyy-MM-dd";

    public static CompanyRegistration createCompanyRegistration(String legalName, PersonType user) {

//        CompanyDetails companyDetails = new CompanyDetails(Collections.singletonMap(NimbleConfigurationProperties.LanguageID.ENGLISH, legalName),
//                new CompanyDetails(Collections.singletonMap(NimbleConfigurationProperties.LanguageID.ENGLISH, legalName),
//                        "vat number", "verification info",
//                        new Address(), "business type", Collections.singletonList("business type"), 1970,
//                        Collections.singletonList("industry sector"));

        CompanyDetails companyDetails = new CompanyDetails(Collections.singletonMap(NimbleConfigurationProperties.LanguageID.ENGLISH, legalName),
                Collections.singletonMap(NimbleConfigurationProperties.LanguageID.ENGLISH, legalName), "vat number", "verification info",
                new Address(), "business type", Collections.singletonMap(NimbleConfigurationProperties.LanguageID.ENGLISH, "keywords"), 1970,
                Collections.singletonList("industry sector"));

        CompanyDescription companyDescription = new CompanyDescription("statement", "website",
                Collections.singletonList("photos"), "imageId", Collections.singletonList("social media"),
                Collections.singletonList(new CompanyEvent()), Collections.singletonList("test"));
        CompanyTradeDetails companyTradeDetails = new CompanyTradeDetails();
        CompanySettings companySettings = new CompanySettings("123", companyDetails, companyDescription,
                companyTradeDetails, Collections.singletonList(new CompanyCertificate()), Collections.singleton("category 1"), Collections.singleton("category 2"));
        CompanyRegistration companyRegistration = new CompanyRegistration();
        companyRegistration.setCompanyID(123L);
        companyRegistration.setUserID(user.getHjid());
        companyRegistration.setAccessToken("DUMMY ACCESSTOKEN");
        companyRegistration.setSettings(companySettings);

        return companyRegistration;
    }
}
