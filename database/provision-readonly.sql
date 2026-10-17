\set ON_ERROR_STOP on
BEGIN;
-- Run as the migration/owner account after Flyway. No secrets in SQL files or argv.
SELECT 'CREATE ROLE analyst_readonly LOGIN' WHERE NOT EXISTS
 (SELECT 1 FROM pg_roles WHERE rolname='analyst_readonly') \gexec
ALTER ROLE analyst_readonly NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
SELECT format('ALTER ROLE analyst_readonly PASSWORD %L', :'analytics_password') \gexec
-- Remove any inherited roles if provisioning is repeated after accidental grant changes.
SELECT format('REVOKE %I FROM analyst_readonly', r.rolname)
 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid
 WHERE m.member='analyst_readonly'::regrole \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC, analyst_readonly', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO analyst_readonly', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC, analyst_readonly;
GRANT USAGE ON SCHEMA public TO analyst_readonly;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, analyst_readonly;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, analyst_readonly;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, analyst_readonly;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC, analyst_readonly;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, analyst_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC, analyst_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, analyst_readonly;
GRANT SELECT ON public.customers, public.categories, public.products, public.orders,
 public.order_items, public.payments, public.refunds TO analyst_readonly;
ALTER ROLE analyst_readonly SET default_transaction_read_only = on;
ALTER ROLE analyst_readonly SET search_path = pg_catalog, public;
ALTER ROLE analyst_readonly SET statement_timeout = '2s';
ALTER ROLE analyst_readonly SET lock_timeout = '500ms';
COMMIT;
