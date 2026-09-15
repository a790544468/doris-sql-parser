-- Default: Doris 2.1
WITH recent AS (
    SELECT customer_id, amount FROM sales.orders WHERE order_date >= '2026-01-01'
)
SELECT customer_id, SUM(amount) AS total
FROM recent GROUP BY customer_id ORDER BY total DESC LIMIT 10;

INSERT INTO report.customer_sales
SELECT customer_id, SUM(amount) FROM sales.orders GROUP BY customer_id;

CREATE TABLE IF NOT EXISTS report.daily (
    id BIGINT NOT NULL COMMENT '主键',
    amount DECIMAL(18, 2) DEFAULT '0' COMMENT '金额'
)
ENGINE = OLAP
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ('replication_num' = '1');
