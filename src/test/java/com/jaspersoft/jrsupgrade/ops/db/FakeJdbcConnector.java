package com.jaspersoft.jrsupgrade.ops.db;

import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link JdbcConnector}: records connections and every executed statement, answers the
 * probe query with a row, and can be told to fail on connect or on a statement containing a given
 * text.
 */
public final class FakeJdbcConnector implements JdbcConnector {

  public final List<String> connections = new ArrayList<>();
  public final List<String> executed = new ArrayList<>();
  public Optional<JdbcException> connectFailure = Optional.empty();
  public Optional<String> failOnStatementContaining = Optional.empty();
  public String product = "FakeDB 1.0";

  @Override
  public Session connect(
      Path driverDir, String url, Optional<String> username, Optional<Secret> password)
      throws JdbcException {
    if (connectFailure.isPresent()) {
      throw connectFailure.get();
    }
    connections.add(url + username.map(u -> " as " + u).orElse(""));
    return new Session() {
      @Override
      public String product() {
        return product;
      }

      @Override
      public boolean queryHasRow(String sql) {
        executed.add(sql);
        return true;
      }

      @Override
      public void executeStatement(String statement) throws JdbcException {
        if (failOnStatementContaining.map(statement::contains).orElse(false)) {
          throw new JdbcException(JdbcException.Kind.SQL_FAILED, "boom: " + statement);
        }
        executed.add(statement);
      }

      @Override
      public void close() {}
    };
  }
}
