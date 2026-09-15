INSERT INTO report.user_profile(user_id, user_name, total_amount, user_level, last_order_time)
SELECT x.*,
       CASE
           WHEN x.total_amount >= 1000 AND vip.user_id IS NOT NULL THEN 'VIP'
           WHEN x.total_amount > 0 THEN 'ACTIVE'
           ELSE 'NEW'
       END AS user_level,
       latest.last_order_time
FROM (
    SELECT u.id AS user_id,
           COALESCE(u.name, 'unknown') AS user_name,
           COALESCE(s.total_amount, 0) AS total_amount
    FROM users AS u
    LEFT JOIN (
        SELECT detail.user_id, SUM(detail.net_amount) AS total_amount
        FROM (
            SELECT o.user_id,
                   CASE WHEN o.status = 'PAID'
                        THEN o.amount - COALESCE(r.refund_amount, 0)
                        ELSE 0 END AS net_amount
            FROM orders AS o
            LEFT JOIN (
                SELECT order_id, SUM(amount) AS refund_amount
                FROM refunds
                WHERE status = 'SUCCESS'
                GROUP BY order_id
            ) AS r ON o.id = r.order_id
            WHERE EXISTS (
                SELECT 1 FROM order_items AS oi
                WHERE oi.order_id = o.id AND oi.quantity > 0
            )
        ) AS detail
        GROUP BY detail.user_id
    ) AS s ON u.id = s.user_id
    WHERE u.enabled = 1
      AND u.id IN (SELECT user_id FROM user_tags WHERE tag = 'target')
) AS x
LEFT JOIN (
    SELECT user_id, MAX(created_at) AS last_order_time
    FROM orders GROUP BY user_id
) AS latest ON x.user_id = latest.user_id
LEFT JOIN (
    SELECT DISTINCT user_id FROM memberships WHERE level = 'VIP'
) AS vip ON x.user_id = vip.user_id;
