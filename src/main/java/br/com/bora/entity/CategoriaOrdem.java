package br.com.bora.entity;
import jakarta.persistence.*; import lombok.*;
/** A posicao de UMA categoria no cardapio de UMA loja. Ver V64. */
@Entity @Table(name="categoria_ordem") @Getter @Setter
public class CategoriaOrdem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="loja_id") public Long lojaId;
    /** O mesmo texto que esta em `produto.categoria` -- e com a MESMA largura (80). */
    @Column(length=80) public String nome;
    public Integer posicao = 0;
}
