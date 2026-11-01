-- 示例业务数据：包含 PII（邮箱/电话/IBAN），用于演示审计切面与 GDPR 脱敏效果。
-- 说明：hibernate ddl-auto=update 建表；此脚本仅在嵌入式/首次初始化时执行。
-- 为避免重复插入，这里使用 WHERE NOT EXISTS 守卫（id 显式指定时幂等）。

INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 1, 'Max', 'Mustermann', 'max.mustermann@example.de', '+49 170 1234567', 'DE89370400440532013000', 'Engineering', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 1);

INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 2, 'Anna', 'Schmidt', 'anna.schmidt@company.de', '+49-170-9876543', 'FR7630006000011234567890189', 'Finance', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 2);

INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 3, 'Jonas', 'Weber', 'jonas.weber@mail.company.co.uk', '0170 5551234', NULL, 'Consulting', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 3);

SELECT setval('users_id_seq', (SELECT MAX(id) FROM users));

INSERT INTO audit_chain_heads (stream_id, next_sequence, last_hash, updated_at, version)
VALUES ('global', 1, repeat('0', 64), now(), 0)
ON CONFLICT (stream_id) DO NOTHING;
