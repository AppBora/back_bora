package br.com.bora.repository;

import br.com.bora.entity.Assinatura;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssinaturaRepository extends JpaRepository<Assinatura, Long> {
    Optional<Assinatura> findByLojaId(Long lojaId);
    Optional<Assinatura> findByAsaasSubscriptionId(String asaasSubscriptionId);
}
