-- decision: D00-4 — docs/step_00_foundations.md#decisions-and-outputs
-- Databases and roles (master §0.3 O3, O12; TB3, TB5). Run by init.sh as the superuser, which nothing else uses.
--
--   per service: <db>_owner  owns the database and every object Flyway creates (migrations only)
--                <db>_app    runtime DML, granted through default privileges of <db>_owner
--   verifier     read-only on every service database (tools/verifier)
--   stats_reader pg_monitor only (pg_stat_statements, pg_stat_activity); no table privileges
\set ON_ERROR_STOP on

CREATE ROLE verifier LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD :'verifier_password';
-- Defense in depth on top of missing write privileges.
ALTER ROLE verifier SET default_transaction_read_only = on;

CREATE ROLE stats_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'stats_password';
GRANT pg_monitor TO stats_reader;

-- The maintenance database holds no data; nobody but the superuser needs it.
REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;

\set db orders
\set owner_password :orders_owner_password
\set app_password :orders_app_password
\ir database.sql

\set db ledger
\set owner_password :ledger_owner_password
\set app_password :ledger_app_password
\ir database.sql

\set db instruments
\set owner_password :instruments_owner_password
\set app_password :instruments_app_password
\ir database.sql

\set db fakeproviders
\set owner_password :fakeproviders_owner_password
\set app_password :fakeproviders_app_password
\ir database.sql
