/**
 * Spring Boot auto-configuration for the Accord server: define a
 * {@link io.github.crossben.accordsync.server.ServerDefinition} bean and the starter serves
 * {@code POST /v1/push}, {@code GET /v1/pull} and {@code GET /health} on the app's web server, with
 * the app's {@link javax.sql.DataSource}. See {@link io.github.crossben.accordsync.spring.AccordProperties}.
 */
package io.github.crossben.accordsync.spring;
