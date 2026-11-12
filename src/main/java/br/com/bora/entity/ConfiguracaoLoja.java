package br.com.bora.entity; import jakarta.persistence.*; import lombok.*; @Entity @Getter @Setter public class ConfiguracaoLoja{@Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; public Long lojaId; public String nomeExibicao; public String nomeSistema; public String logoUrl; public String corPrimaria; public String corSecundaria; public String bannerUrl; public String subdominio; public Boolean mostrarMarcaBora=true;
 /** Cashback devolvido ao cliente, em % do valor pago. 0 desliga. Padrao 5. */
 @Column(name="cashback_percentual") public java.math.BigDecimal cashbackPercentual;
 /** Aliquota de imposto sobre o faturamento, em %. Sem ela o lucro sai sem imposto e engana. */
 @Column(name="aliquota_imposto") public java.math.BigDecimal aliquotaImposto;
 /** Custo fixo mensal da loja (aluguel, folha, energia). Rateado pelos dias do periodo. */
 @Column(name="custo_fixo_mensal") public java.math.BigDecimal custoFixoMensal;
 /**
  * O nome REAL da loja (de `loja.nome`), so para leitura. Nao e coluna desta tabela.
  *
  * <p>Existe por causa de uma rede de seguranca do painel que estava morta. Quando o lojista APAGA
  * o campo "nome de exibicao", `nomeExibicao` fica vazio e o `whitelabel.js` caia no texto do HTML.
  * O desvio que ele tentava -- o nome da loja guardado na sessao -- nunca funcionou: a sessao guarda
  * so {nome, papel, lojaId}, e `lojaNome` so vinha de dois endpoints, nenhum deles o login. Medido
  * em 31/10/2026.</p>
  *
  * <p>Entao o nome chega pela resposta que a tela JA pede, em vez de uma segunda chamada a cada
  * pagina. Transiente: a tabela nao ganha coluna, e `ddl-auto: validate` continua valendo.</p>
  */
 @Transient public String nomeDaLoja;}
