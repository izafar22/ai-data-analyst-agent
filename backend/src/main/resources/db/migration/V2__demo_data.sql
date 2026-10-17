-- Fixed UTC dates make this dataset identical on every installation.
INSERT INTO categories(name) VALUES ('Electronics'), ('Home'), ('Outdoors');
INSERT INTO products(category_id,name,price) VALUES
 (1,'Wireless headphones',89.00),(1,'USB-C hub',45.00),
 (2,'Desk lamp',32.50),(2,'Coffee grinder',64.00),
 (3,'Daypack',55.00),(3,'Water bottle',24.00);
INSERT INTO customers(name,email,created_at)
 SELECT 'Demo Customer ' || n, 'customer' || n || '@example.test',
        TIMESTAMPTZ '2026-07-01 09:00:00+00' + n * INTERVAL '1 day'
 FROM generate_series(1,12) n;
INSERT INTO orders(customer_id,status,ordered_at)
 SELECT (n-1)%12+1,
        (ARRAY['PAID','SHIPPED','DELIVERED','PENDING','CANCELLED'])[(n-1)%5+1],
        TIMESTAMPTZ '2026-09-01 10:00:00+00' + (n-1) * INTERVAL '1 day'
 FROM generate_series(1,45) n;
INSERT INTO order_items(order_id,product_id,quantity,unit_price)
 SELECT o.id, p.id, (o.id%3+1)::integer, p.price
 FROM orders o JOIN products p ON p.id IN ((o.id-1)%6+1, o.id%6+1)
 ORDER BY o.id,p.id;
INSERT INTO payments(order_id,amount,status,paid_at)
 SELECT o.id, SUM(i.quantity*i.unit_price),
        CASE WHEN o.status IN ('PAID','SHIPPED','DELIVERED') THEN 'SUCCEEDED'
             WHEN o.status = 'PENDING' THEN 'PENDING' ELSE 'FAILED' END,
        CASE WHEN o.status IN ('PAID','SHIPPED','DELIVERED') THEN o.ordered_at + INTERVAL '5 minutes' END
 FROM orders o JOIN order_items i ON i.order_id=o.id
 GROUP BY o.id ORDER BY o.id;
INSERT INTO refunds(payment_id,amount,refunded_at)
 SELECT id, round(amount/4,2), paid_at + INTERVAL '2 days'
 FROM payments WHERE status='SUCCEEDED' AND order_id IN (3,13,23);
