package dev.squaddesktop.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Logger;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.squaddesktop.config.SquadSettings;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    private static final Logger LOG = Logger.getLogger(WebConfiguration.class.getName());

    private final SquadSettings settings;

    public WebConfiguration(SquadSettings settings) {
        this.settings = settings;
    }

    @Bean
    FilterRegistrationBean<LocalAccessFilter> localAccessFilter() {
        String token = settings.authToken();
        if (token == null) {
            // Sem token informado (execução manual do backend): gera um aleatório. A API fica inacessível
            // até alguém iniciá-lo com SQUAD_AUTH_TOKEN, o que é o comportamento seguro.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            token = HexFormat.of().formatHex(random);
            LOG.warning("SQUAD_AUTH_TOKEN não definido: a API só aceitará chamadas quando o serviço for iniciado pelo aplicativo ou por scripts/dev.mjs.");
        }
        FilterRegistrationBean<LocalAccessFilter> registration = new FilterRegistrationBean<>(
                new LocalAccessFilter(token, settings.allowedOrigins()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Serve a interface compilada (quando informada) na raiz, com fallback para index.html. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (settings.staticDir() == null) {
            return;
        }
        Path dir = Path.of(settings.staticDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            LOG.warning("Pasta da interface não encontrada: " + dir);
            return;
        }
        registry.addResourceHandler("/**").addResourceLocations(dir.toUri().toString());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (settings.staticDir() != null) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }
}
