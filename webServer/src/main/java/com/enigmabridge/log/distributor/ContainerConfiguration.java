package com.enigmabridge.log.distributor;

import com.enigmabridge.log.distributor.api.ApiConfig;
import org.apache.coyote.http11.Http11NioProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.io.File;

@Configuration
@DependsOn(value = ApiConfig.YAML_CONFIG)
public class ContainerConfiguration {
    private final static Logger LOG = LoggerFactory.getLogger(ContainerConfiguration.class);

    @Bean
    @DependsOn(value = ApiConfig.YAML_CONFIG)
    EmbeddedServletContainerCustomizer containerCustomizer(
            @Value("${keystore.file}") String keystoreFile,
            @Value("${server.port}") final String serverPort,
            @Value("${keystore.pass}") final String keystorePass)
            throws Exception {

        // This is boiler plate code to setup https on embedded Tomcat
        // with Spring Boot:

        final String absoluteKeystoreFile = new File(keystoreFile)
                .getAbsolutePath();

        LOG.info("Starting HTTPS endpoint on port: {}", serverPort);
        return container -> {
            TomcatEmbeddedServletContainerFactory tomcat = (TomcatEmbeddedServletContainerFactory) container;
            tomcat.addConnectorCustomizers(connector -> {
                connector.setPort(Integer.parseInt(serverPort));
                connector.setSecure(true);
                connector.setScheme("https");

                Http11NioProtocol proto = (Http11NioProtocol) connector
                        .getProtocolHandler();
                proto.setSSLEnabled(true);

                proto.setKeystoreFile(absoluteKeystoreFile);
                proto.setKeystorePass(keystorePass);
                proto.setKeystoreType("JKS");
                proto.setKeyAlias("tomcat");
            });
        };
    }
}
