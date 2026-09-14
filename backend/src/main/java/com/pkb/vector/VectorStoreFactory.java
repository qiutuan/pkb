package com.pkb.vector;

import com.pkb.config.PkbProperties;
import com.pkb.vector.embedded.EmbeddedVectorStore;
import com.pkb.vector.pg.PgVectorStore;
import org.springframework.stereotype.Component;

/**
 * 向量存储工厂：按 pkb.vector.mode 切换（embedded | pgvector），业务代码零改动。
 */
@Component
public class VectorStoreFactory {

    private final PkbProperties props;
    private final EmbeddedVectorStore embedded;
    private final PgVectorStore pg;

    public VectorStoreFactory(PkbProperties props, EmbeddedVectorStore embedded, PgVectorStore pg) {
        this.props = props;
        this.embedded = embedded;
        this.pg = pg;
    }

    public VectorStore get() {
        return "pgvector".equalsIgnoreCase(props.getVector().getMode()) ? pg : embedded;
    }

    public String mode() {
        return props.getVector().getMode();
    }
}
