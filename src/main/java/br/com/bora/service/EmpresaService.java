package br.com.bora.service;

import br.com.bora.entity.Empresa;
import br.com.bora.entity.Loja;
import br.com.bora.repository.EmpresaRepository;
import br.com.bora.repository.LojaRepository;
import org.springframework.stereotype.Service;

/**
 * Descobre a qual empresa uma loja pertence. Mesmo CNPJ = mesma empresa; loja sem documento
 * ganha empresa própria (não dá para adivinhar a dona, e chutar juntaria clientes diferentes).
 */
@Service
public class EmpresaService {

    private final EmpresaRepository empresas;
    private final LojaRepository lojas;

    public EmpresaService(EmpresaRepository empresas, LojaRepository lojas) {
        this.empresas = empresas;
        this.lojas = lojas;
    }

    /** Só dígitos — "53.953.786/0001-28" e "53953786000128" são o mesmo CNPJ. */
    public static String normalizar(String documento) {
        if (documento == null) return null;
        String d = documento.replaceAll("\\D", "");
        return d.isBlank() ? null : d;
    }

    /** Empresa da loja: reaproveita a do CNPJ quando já existe, senão cria. */
    public Empresa paraDocumento(String documento, String nomeFallback) {
        String cnpj = normalizar(documento);
        if (cnpj != null) {
            Empresa existente = empresas.findByCnpj(cnpj).orElse(null);
            if (existente != null) return existente;
        }
        Empresa e = new Empresa();
        e.setRazaoSocial(nomeFallback == null || nomeFallback.isBlank() ? "Empresa" : nomeFallback.trim());
        e.setCnpj(cnpj);
        return empresas.save(e);
    }

    /**
     * Duas lojas são da mesma dona? É a pergunta que autoriza (ou recusa) vincular um usuário
     * a outra loja. Loja sem empresa responde não — melhor recusar do que unir clientes distintos.
     */
    public boolean mesmaEmpresa(Long lojaA, Long lojaB) {
        if (lojaA == null || lojaB == null) return false;
        if (lojaA.equals(lojaB)) return true;
        Long a = lojas.findById(lojaA).map(Loja::getEmpresaId).orElse(null);
        Long b = lojas.findById(lojaB).map(Loja::getEmpresaId).orElse(null);
        return a != null && a.equals(b);
    }
}
