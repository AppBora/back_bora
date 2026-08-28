package br.com.bora.controller;

import br.com.bora.service.OnboardingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Prontidão de operação da loja para o assistente de onboarding no painel. */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status() {
        return service.status();
    }
}
