ALTER TABLE builder_conversations ADD COLUMN idempotency_key VARCHAR(120);
UPDATE builder_conversations SET idempotency_key = 'migrated:' || id::text WHERE idempotency_key IS NULL;
ALTER TABLE builder_conversations ALTER COLUMN idempotency_key SET NOT NULL;
CREATE UNIQUE INDEX uq_builder_conversation_idempotency ON builder_conversations(workspace_id, idempotency_key);

ALTER TABLE builder_workflows ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
