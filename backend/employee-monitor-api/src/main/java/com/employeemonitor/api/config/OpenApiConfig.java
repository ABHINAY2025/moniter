package com.employeemonitor.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI employeeMonitorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Employee Monitor API")
                        .version("0.1.0")
                        .description("Backend for the transparent, policy-governed employee monitoring platform. "
                                + "Admin endpoints use a user JWT; /api/agent/** endpoints use a device token (dvt_...)."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
