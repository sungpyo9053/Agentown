alter table builder_conversations
    add column purpose varchar(40) not null default 'AUTOMATION';

create index idx_builder_conversations_workspace_purpose_created
    on builder_conversations(workspace_id, purpose, created_at desc);
