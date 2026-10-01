package com.jaspersoft.jrsupgrade.ops.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlScriptTest {

  @Test
  void should_split_on_semicolon_at_line_end_and_drop_comments() {
    String script =
        "-- header comment\n"
            + "CREATE TABLE t (\n"
            + "  id int, -- inline; stays\n"
            + "  name varchar(10)\n"
            + ");\n"
            + "\n"
            + "  -- another comment\n"
            + "INSERT INTO t VALUES (1, 'a;b');\n"
            + "UPDATE t SET name = 'x'\n"
            + "WHERE id = 1;   \n"
            + "SELECT 1";
    assertThat(SqlScript.statements(script))
        .containsExactly(
            "CREATE TABLE t (\n  id int, -- inline; stays\n  name varchar(10)\n)",
            "INSERT INTO t VALUES (1, 'a;b')",
            "UPDATE t SET name = 'x'\nWHERE id = 1",
            "SELECT 1");
  }

  @Test
  void should_return_nothing_when_script_is_only_comments_and_blank_lines() {
    assertThat(SqlScript.statements("-- a\n\n   \n-- b;\n")).isEmpty();
    assertThat(SqlScript.statements(";\n;\n")).isEmpty();
  }

  @Test
  void should_split_two_statements_on_one_line_when_both_are_terminated() {
    assertThat(SqlScript.statements("UPDATE t SET a = 1; UPDATE t SET b = 2;"))
        .containsExactly("UPDATE t SET a = 1", "UPDATE t SET b = 2");
  }

  @Test
  void should_keep_a_semicolon_inside_a_string_literal_in_one_statement() {
    assertThat(SqlScript.statements("INSERT INTO t VALUES ('ends with ;');"))
        .containsExactly("INSERT INTO t VALUES ('ends with ;')");
    assertThat(SqlScript.statements("INSERT INTO t VALUES ('it''s a ; here');"))
        .as("a doubled quote does not close the literal")
        .containsExactly("INSERT INTO t VALUES ('it''s a ; here')");
  }

  @Test
  void should_keep_a_semicolon_inside_a_quoted_identifier_in_one_statement() {
    assertThat(SqlScript.statements("ALTER TABLE \"odd;name\" ADD c int;"))
        .containsExactly("ALTER TABLE \"odd;name\" ADD c int");
    assertThat(SqlScript.statements("ALTER TABLE `odd;name` ADD c int;"))
        .containsExactly("ALTER TABLE `odd;name` ADD c int");
  }

  @Test
  void should_keep_a_semicolon_inside_a_dollar_quoted_body_in_one_statement() {
    String script =
        "CREATE FUNCTION f() RETURNS void AS $$\n"
            + "BEGIN\n"
            + "  UPDATE t SET a = 1;\n"
            + "  UPDATE t SET b = 2;\n"
            + "END;\n"
            + "$$ LANGUAGE plpgsql;\n";

    assertThat(SqlScript.statements(script)).hasSize(1);
    assertThat(SqlScript.statements(script).get(0))
        .startsWith("CREATE FUNCTION f()")
        .endsWith("LANGUAGE plpgsql")
        .contains("UPDATE t SET b = 2;");
  }

  @Test
  void should_close_a_dollar_quote_only_on_its_own_tag() {
    String script = "SELECT $body$ a $other$ b ; c $body$;";
    assertThat(SqlScript.statements(script))
        .containsExactly("SELECT $body$ a $other$ b ; c $body$");
  }

  @Test
  void should_keep_a_semicolon_inside_a_block_comment_in_one_statement() {
    assertThat(SqlScript.statements("UPDATE t /* not a ; terminator */ SET a = 1;"))
        .containsExactly("UPDATE t /* not a ; terminator */ SET a = 1");
    assertThat(SqlScript.statements("/* leading ; comment */\nSELECT 1;"))
        .as("a comment before a statement is dropped with the blank lines")
        .containsExactly("SELECT 1");
  }

  @Test
  void should_end_statements_at_the_declared_delimiter_when_a_directive_sets_one() {
    String script =
        "UPDATE t SET a = 0;\n"
            + "-- jrs-upgrade:delimiter //\n"
            + "CREATE PROCEDURE p AS BEGIN UPDATE t SET a = 1; UPDATE t SET b = 2; END;\n"
            + "//\n"
            + "-- jrs-upgrade:delimiter ;\n"
            + "UPDATE t SET c = 3;\n";

    assertThat(SqlScript.statements(script))
        .containsExactly(
            "UPDATE t SET a = 0",
            "CREATE PROCEDURE p AS BEGIN UPDATE t SET a = 1; UPDATE t SET b = 2; END;",
            "UPDATE t SET c = 3");
  }

  @Test
  void should_keep_a_dollar_sign_that_starts_no_quote_as_ordinary_text() {
    assertThat(SqlScript.statements("UPDATE t SET a = 'x' WHERE b = $1;"))
        .containsExactly("UPDATE t SET a = 'x' WHERE b = $1");
    assertThat(SqlScript.statements("SELECT cost$ FROM t;")).containsExactly("SELECT cost$ FROM t");
  }
}
