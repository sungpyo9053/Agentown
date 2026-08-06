CREATE TABLE product_reviews (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES market_products(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_review_user UNIQUE (product_id, user_id),
    CONSTRAINT ck_product_review_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX idx_product_reviews_product_created ON product_reviews(product_id, created_at DESC);
