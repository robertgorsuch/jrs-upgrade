package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

/** Issue #61: every command's {@code --help} ends with examples that really parse. */
class HelpExamplesTest {

  private static final Pattern BLANK = Pattern.compile("\\s+");

  @Test
  void should_have_examples_for_every_runnable_command_when_tree_is_built_by_main() {
    List<String> missing = new ArrayList<>();
    for (CommandLine cmd : all()) {
      CommandSpec spec = cmd.getCommandSpec();
      String path = relativePath(spec);
      if (path.equals("help") || spec.parent() == null) {
        continue;
      }
      if (runnable(spec) && HelpExamples.examples(path).isEmpty()) {
        missing.add(path);
      }
    }
    assertThat(missing).as("commands without examples").isEmpty();
  }

  @Test
  void should_parse_every_example_as_the_command_it_documents_when_run_against_the_tree() {
    for (Map.Entry<String, List<HelpExamples.Example>> e : HelpExamples.all().entrySet()) {
      for (HelpExamples.Example example : e.getValue()) {
        List<String> words = List.of(BLANK.split(example.command().trim()));
        assertThat(words.get(0)).as(example.command()).isEqualTo("jrs-upgrade");
        ParseResult parsed =
            Main.commandLine().parseArgs(words.subList(1, words.size()).toArray(String[]::new));
        while (parsed.hasSubcommand()) {
          parsed = parsed.subcommand();
        }
        if (!e.getKey().isEmpty()) {
          // the root's examples are an overview of first steps, each naming its own command
          assertThat(relativePath(parsed.commandSpec()))
              .as(example.command())
              .isEqualTo(e.getKey());
        }
      }
    }
  }

  @Test
  void should_end_help_with_examples_when_command_has_them() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));

    assertThat(cmd.execute("upgrade", "--help")).isZero();

    String help = out.toString();
    assertThat(help).contains("Examples:").contains("jrs-upgrade upgrade");
    assertThat(help.indexOf("Examples:")).isGreaterThan(help.indexOf("--plan"));
  }

  @Test
  void should_point_to_subcommand_help_when_group_has_no_examples_of_its_own() {
    StringWriter out = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new PrintWriter(out));

    assertThat(cmd.execute("runs", "--help")).isZero();

    assertThat(out.toString()).contains("jrs-upgrade runs <command> --help");
  }

  private static List<CommandLine> all() {
    List<CommandLine> out = new ArrayList<>();
    collect(Main.commandLine(), out);
    return out;
  }

  private static void collect(CommandLine cmd, List<CommandLine> out) {
    out.add(cmd);
    for (CommandLine sub : cmd.getSubcommands().values()) {
      if (!out.contains(sub)) {
        collect(sub, out);
      }
    }
  }

  /**
   * Leaves, and groups that take options or parameters of their own (e.g. {@code upgrade}); the
   * same rule {@code ExplainTest} uses for commands that need a guide section.
   */
  private static boolean runnable(CommandSpec spec) {
    if (spec.subcommands().isEmpty()) {
      return true;
    }
    boolean ownOptions =
        spec.options().stream()
            .anyMatch(
                o -> !o.usageHelp() && !o.versionHelp() && !o.longestName().equals(Explain.OPTION));
    return ownOptions || !spec.positionalParameters().isEmpty();
  }

  private static String relativePath(CommandSpec spec) {
    String full = spec.qualifiedName(" ");
    return full.equals("jrs-upgrade") ? "" : full.substring("jrs-upgrade ".length());
  }
}
