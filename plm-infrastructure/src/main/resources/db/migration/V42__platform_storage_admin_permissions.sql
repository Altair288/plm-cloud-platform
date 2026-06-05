INSERT INTO plm_platform.permission (id, permission_code, permission_name, scope_type, module_code, description, created_by)
VALUES
    (gen_random_uuid(), 'platform.storage.cluster.manage', '平台侧管理存储集群', 'GLOBAL', 'platform.storage', '管理对象存储集群配置', 'FLYWAY_V42'),
    (gen_random_uuid(), 'platform.storage.cluster.test', '平台侧测试存储集群', 'GLOBAL', 'platform.storage', '测试对象存储连接、上传与下载链路', 'FLYWAY_V42')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO plm_platform.platform_role_permission (role_id, permission_id)
SELECT pr.id, p.id
FROM plm_platform.platform_role pr
JOIN plm_platform.permission p ON (
       (pr.role_code IN ('platform_super_admin', 'platform_admin')
           AND p.permission_code IN ('platform.storage.cluster.manage', 'platform.storage.cluster.test'))
    OR (pr.role_code = 'platform_operator'
           AND p.permission_code IN ('platform.storage.cluster.test'))
)
ON CONFLICT DO NOTHING;