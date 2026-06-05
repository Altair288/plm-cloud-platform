INSERT INTO plm_platform.permission (id, permission_code, permission_name, scope_type, module_code, description, created_by)
VALUES
    (gen_random_uuid(), 'storage.bucket.read', '对象存储查看 bucket 元数据', 'WORKSPACE', 'storage.bucket', '查看 workspace bucket 元数据', 'FLYWAY_V41'),
    (gen_random_uuid(), 'storage.object.upload', '对象存储上传对象', 'WORKSPACE', 'storage.object', '创建上传申请并上传对象', 'FLYWAY_V41'),
    (gen_random_uuid(), 'storage.object.download', '对象存储下载对象', 'WORKSPACE', 'storage.object', '申请对象下载链接', 'FLYWAY_V41'),
    (gen_random_uuid(), 'storage.object.delete', '对象存储删除对象', 'WORKSPACE', 'storage.object', '删除对象', 'FLYWAY_V41'),
    (gen_random_uuid(), 'storage.object.manage', '对象存储管理对象', 'WORKSPACE', 'storage.object', '管理对象生命周期与元数据', 'FLYWAY_V41')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO plm_platform.workspace_role_permission (workspace_role_id, permission_id)
SELECT wr.id, p.id
FROM plm_platform.workspace_role wr
JOIN plm_platform.permission p ON (
       (wr.role_code IN ('workspace_owner', 'workspace_admin')
           AND p.permission_code IN ('storage.bucket.read', 'storage.object.upload', 'storage.object.download', 'storage.object.delete', 'storage.object.manage'))
    OR (wr.role_code = 'workspace_member'
           AND p.permission_code IN ('storage.bucket.read', 'storage.object.upload', 'storage.object.download'))
    OR (wr.role_code = 'workspace_viewer'
           AND p.permission_code IN ('storage.bucket.read', 'storage.object.download'))
)
ON CONFLICT DO NOTHING;