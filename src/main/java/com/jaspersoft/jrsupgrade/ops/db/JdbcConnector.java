package com.jaspersoft.jrsupgrade.ops.db;

import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Opens JDBC sessions against the JasperReports repository database using driver jars that are not
 * on jrs-upgrade's own class path (spec §8.2 step 9, §12.1 doctor). Invariants: the password never
 * leaves the {@link Secret} except into the driver's connection properties, which are cleared right
 * after the connection is established; every session owns its driver class loader and releases it
 * on {@link Session#close()}; statements run in the order the script lists them.
 */
public interface JdbcConnector {

  /**
   * Loads every {@code *.jar} under {@code driverDir}, picks the first {@code java.sql.Driver} that
   * accepts {@code url} and connects. Callers close the session in try-with-resources.
   */
  Session connect(Path driverDir, String url, Optional<String> username, Optional<Secret> password)
      throws JdbcException;

  /** One open connection plus the class loader that hosts its driver. */
  interface Session extends AutoCloseable {

    /** Database product name and version as the driver reports them. */
    String product();

    /** True when {@code sql} returns at least one row. */
    boolean queryHasRow(String sql) throws JdbcException;

    /**
     * Sends exactly one statement, already split out by {@link SqlScript}, to the driver as-is.
     * Never re-splits: a {@code -- jrs-upgrade:delimiter} directive is only visible to the reader
     * that saw the whole script, so a statement whose body holds semicolons must arrive here whole.
     */
    void executeStatement(String statement) throws JdbcException;

    /**
     * The tables and sequences of the connection's own schema, from the driver's metadata (issue
     * #3); read-only. Drivers that report sequences through other means (Oracle) list tables only.
     */
    List<DbObject> objects() throws JdbcException;

    /**
     * A {@code CREATE TABLE} statement rebuilt from the driver's metadata for {@code table}: column
     * names, types, sizes, nullability and the primary key. An approximation for the operator to
     * check, not the original DDL (no defaults, indexes, constraints beyond the key); read-only.
     */
    String tableDdl(String table) throws JdbcException;

    @Override
    void close() throws JdbcException;
  }
}
