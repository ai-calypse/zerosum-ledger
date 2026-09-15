-- decision: D00-4 — docs/step_00_foundations.md#decisions-and-outputs
-- Per-service database template, included by init.sql with :db, :owner_password and :app_password set.
\set owner :db _owner
\set app :db _app

CREATE ROLE :"owner" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';

CREATE DATABASE :"db" OWNER :"owner";

-- PostgreSQL grants CONNECT and TEMPORARY to PUBLIC by default; confine the database to its own service.
REVOKE CONNECT, TEMPORARY ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"owner", :"app", verifier, stats_reader;

\connect :db

-- Explicit even though PostgreSQL 15+ no longer grants it: only the owner creates objects.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Tables Flyway creates later (as the owner) become writable by the app role and readable by verifier.
-- Grants go to named roles, never PUBLIC, so S02/S03 can REVOKE them per table from the app role.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO verifier;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON SEQUENCES TO verifier;

-- Preloaded by the server settings in docker-compose.yml (D00-3); created here as the superuser (O12).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

\connect postgres
