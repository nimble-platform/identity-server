package eu.nimble.core.infrastructure.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwagger2
public class SwaggerConfig {


    @Value("${nimble.platformHost}")
    private String platformHost;
    @Bean
    public Docket api() {

        platformHost = platformHost.replace("https://", "");
        platformHost = platformHost.replace("http://","");

        return new Docket(DocumentationType.SWAGGER_2)
                .host(platformHost)
                .select()
                .apis(RequestHandlerSelectors.basePackage("eu.nimble.core"))
                .paths(PathSelectors.any())
                .build()
                .apiInfo(metaData())    ;
    }

    private ApiInfo metaData() {
        return new ApiInfo(
                "NIMBLE identity REST API",
                "REST API handling identities on the NIMBLE platform",
                "1.0",
                null,
                new Contact("Johannes Innerbichler", null, "johannes.innerbichler@salzburgresearch.at"),
                "Apache License Version 2.0",
                "https://www.apache.org/licenses/LICENSE-2.0");
    }
}