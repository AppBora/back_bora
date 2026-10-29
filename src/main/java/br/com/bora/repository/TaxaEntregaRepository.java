package br.com.bora.repository;
import br.com.bora.entity.TaxaEntrega;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface TaxaEntregaRepository extends JpaRepository<TaxaEntrega, Long> {
    List<TaxaEntrega> findByLojaIdOrderByBairroAsc(Long lojaId);
    /**
     * Bairros que a loja atende hoje — o cardapio publico mostra so os ativos.
     *
     * <p>Ordenado tambem por {@code id} DESC, e isso nao e enfeite: a tabela nao tem UNIQUE em
     * (loja, bairro), entao o mesmo bairro pode ter duas linhas com fretes diferentes. Sem ordem
     * definida, a linha que a tela MOSTRA podia nao ser a que o servidor COBRA -- o Postgres pode
     * devolver qualquer uma das duas. Com a mesma ordem nas duas consultas, as duas escolhem a
     * mesma: a ultima que o lojista gravou.</p>
     */
    List<TaxaEntrega> findByLojaIdAndAtivoTrueOrderByBairroAscIdDesc(Long lojaId);
    Optional<TaxaEntrega> findByIdAndLojaId(Long id, Long lojaId);

    /**
     * O frete DAQUELE bairro — a consulta que decide quanto o cliente paga.
     *
     * <p>{@code OrderByIdDesc}: era um {@code findFirst} sem ordem nenhuma. Com duas linhas para o
     * mesmo bairro, o frete cobrado podia mudar de uma chamada para a outra, sem nada ter mudado no
     * cadastro. A ultima gravada ganha, que e a que o lojista mexeu por ultimo.</p>
     */
    Optional<TaxaEntrega> findFirstByLojaIdAndBairroIgnoreCaseAndAtivoTrueOrderByIdDesc(Long lojaId, String bairro);

    /** Qualquer linha daquele bairro, ATIVA OU NAO: e por ela que o cadastro evita criar a segunda. */
    Optional<TaxaEntrega> findFirstByLojaIdAndBairroIgnoreCaseOrderByIdDesc(Long lojaId, String bairro);
}
