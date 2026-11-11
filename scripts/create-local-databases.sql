-- Run with psql as a PostgreSQL administrator, connected to the postgres database.
-- Do not wrap this file in a transaction: CREATE DATABASE requires autocommit.
\set ON_ERROR_STOP on

-- Refuse to adopt a database owned by an unrelated role before making changes.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_database
        WHERE datname IN ('event_planner', 'event_planner_test')
          AND pg_get_userbyid(datdba) <> CASE datname
              WHEN 'event_planner' THEN 'planner' ELSE 'planner_test' END
    ) THEN
        RAISE EXCEPTION 'Database owner mismatch. Inspect the existing databases before setup.';
    END IF;
END
$$;

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'planner') AS planner_exists
\gset
\if :planner_exists
    \echo 'Role planner already exists; its password is unchanged.'
\else
    CREATE ROLE planner LOGIN;
    -- psql prompts twice and does not store the plain password in this file.
    \password planner
\endif

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'planner_test') AS planner_test_exists
\gset
\if :planner_test_exists
    \echo 'Role planner_test already exists; its password is unchanged.'
\else
    CREATE ROLE planner_test LOGIN;
    \password planner_test
\endif

SELECT 'CREATE DATABASE event_planner OWNER planner TEMPLATE template0 ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'event_planner')
\gexec

SELECT 'CREATE DATABASE event_planner_test OWNER planner_test TEMPLATE template0 ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'event_planner_test')
\gexec

\echo 'Application and test databases are ready. Flyway creates tables on first use.'
