CREATE TABLE output_templates (
    id UUID PRIMARY KEY,
    template_key VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    active_version_no INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE output_template_versions (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES output_templates(id) ON DELETE RESTRICT,
    version_no INTEGER NOT NULL,
    state VARCHAR(30) NOT NULL,
    source VARCHAR(30) NOT NULL,
    notion_page_id VARCHAR(100),
    content_hash VARCHAR(64) NOT NULL,
    intent_examples_json JSONB NOT NULL,
    required_facts_json JSONB NOT NULL,
    template_definition_json JSONB NOT NULL,
    output_schema_json JSONB NOT NULL,
    acceptance_cases_json JSONB NOT NULL,
    execution_contract_json JSONB NOT NULL,
    validation_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_output_template_version UNIQUE(template_id, version_no),
    CONSTRAINT uq_output_template_content UNIQUE(template_id, content_hash)
);
CREATE INDEX idx_output_template_status ON output_templates(status, category);

CREATE TABLE output_template_sync_runs (
    id UUID PRIMARY KEY,
    source VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    imported_count INTEGER NOT NULL,
    rejected_count INTEGER NOT NULL,
    failure_summary VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

ALTER TABLE builder_workflow_versions
    ADD COLUMN template_version_id UUID REFERENCES output_template_versions(id) ON DELETE RESTRICT,
    ADD COLUMN execution_contract_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE builder_runs
    ADD COLUMN template_version_id UUID REFERENCES output_template_versions(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION protect_approved_output_template_version()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
        (OLD.state = 'DRAFT' AND NEW.state = 'PREVIEWED') OR
        (OLD.state = 'PREVIEWED' AND NEW.state = 'APPROVED') OR
        (OLD.state = 'APPROVED' AND NEW.state IN ('ACTIVE', 'DEPRECATED')) OR
        (OLD.state = 'ACTIVE' AND NEW.state = 'APPROVED')
    ) THEN
        RAISE EXCEPTION 'invalid output template state transition: % -> %', OLD.state, NEW.state;
    END IF;
    IF OLD.state IN ('APPROVED', 'ACTIVE', 'DEPRECATED') AND (
        NEW.template_id IS DISTINCT FROM OLD.template_id OR
        NEW.version_no IS DISTINCT FROM OLD.version_no OR
        NEW.source IS DISTINCT FROM OLD.source OR
        NEW.content_hash IS DISTINCT FROM OLD.content_hash OR
        NEW.intent_examples_json IS DISTINCT FROM OLD.intent_examples_json OR
        NEW.required_facts_json IS DISTINCT FROM OLD.required_facts_json OR
        NEW.template_definition_json IS DISTINCT FROM OLD.template_definition_json OR
        NEW.output_schema_json IS DISTINCT FROM OLD.output_schema_json OR
        NEW.acceptance_cases_json IS DISTINCT FROM OLD.acceptance_cases_json OR
        NEW.execution_contract_json IS DISTINCT FROM OLD.execution_contract_json OR
        NEW.validation_json IS DISTINCT FROM OLD.validation_json
    ) THEN
        RAISE EXCEPTION 'approved output template versions are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_approved_output_template_version
BEFORE UPDATE ON output_template_versions
FOR EACH ROW EXECUTE FUNCTION protect_approved_output_template_version();

CREATE OR REPLACE FUNCTION prevent_output_template_version_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'output template versions must be deprecated, not deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_output_template_version_delete
BEFORE DELETE ON output_template_versions
FOR EACH ROW EXECUTE FUNCTION prevent_output_template_version_delete();
