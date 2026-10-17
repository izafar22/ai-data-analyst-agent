CREATE TABLE customers (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name varchar(150) NOT NULL,
 email varchar(254) NOT NULL UNIQUE,
 created_at timestamptz NOT NULL
);
CREATE TABLE categories (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name varchar(100) NOT NULL UNIQUE
);
CREATE TABLE products (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 category_id bigint NOT NULL REFERENCES categories(id),
 name varchar(200) NOT NULL,
 price numeric(12,2) NOT NULL CHECK (price >= 0)
);
CREATE TABLE orders (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 customer_id bigint NOT NULL REFERENCES customers(id),
 status varchar(20) NOT NULL CHECK (status IN ('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED')),
 ordered_at timestamptz NOT NULL
);
CREATE TABLE order_items (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id bigint NOT NULL REFERENCES orders(id),
 product_id bigint NOT NULL REFERENCES products(id),
 quantity integer NOT NULL CHECK (quantity > 0),
 unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
 UNIQUE(order_id, product_id)
);
CREATE TABLE payments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id bigint NOT NULL REFERENCES orders(id),
 amount numeric(12,2) NOT NULL CHECK (amount > 0),
 status varchar(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
 paid_at timestamptz,
 CHECK ((status = 'SUCCEEDED') = (paid_at IS NOT NULL))
);
CREATE TABLE refunds (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id bigint NOT NULL REFERENCES payments(id),
 amount numeric(12,2) NOT NULL CHECK (amount > 0),
 refunded_at timestamptz NOT NULL
);
CREATE INDEX products_category_idx ON products(category_id);
CREATE INDEX orders_customer_idx ON orders(customer_id);
CREATE INDEX orders_ordered_at_idx ON orders(ordered_at);
CREATE INDEX orders_status_idx ON orders(status);
CREATE INDEX order_items_product_idx ON order_items(product_id);
CREATE INDEX payments_order_idx ON payments(order_id);
CREATE INDEX refunds_payment_idx ON refunds(payment_id);
