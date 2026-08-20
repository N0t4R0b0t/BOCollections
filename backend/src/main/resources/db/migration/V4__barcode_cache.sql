CREATE TABLE resolved_barcodes (
    id BIGSERIAL PRIMARY KEY,
    barcode VARCHAR(32) NOT NULL UNIQUE,
    found BOOLEAN NOT NULL,
    category VARCHAR(10),
    format VARCHAR(50),
    title VARCHAR(255),
    subtitle VARCHAR(255),
    description TEXT,
    cover_url VARCHAR(500),
    release_year INT,
    publisher VARCHAR(255),
    external_id VARCHAR(100),
    source VARCHAR(20),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
