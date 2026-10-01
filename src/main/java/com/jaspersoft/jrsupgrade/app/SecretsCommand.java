package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.state.AuditActor;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade secrets init|set|remove|list} over {@code $JRS_UPGRADE_HOME/secrets.enc} (spec
 * §5.2). Invariants: the passphrase comes from the same chain every command uses ({@code
 * --passphrase-file}, {@code JRS_UPGRADE_PASSPHRASE}, then the console when interactive), so a
 * non-interactive caller without one exits 2 with the remediation; a value is read from an
 * environment variable, a file or stdin into a {@code char[]} that is zeroed afterwards and is
 * never echoed or printed; {@code list} and {@code remove} need no passphrase; every change is
 * audited by name only.
 */
@Command(
    name = "secrets",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Manage the encrypted secret store referenced by enc:NAME.",
    subcommands = {
      SecretsCommand.Init.class,
      SecretsCommand.Set.class,
      SecretsCommand.Remove.class,
      SecretsCommand.ListSecrets.class
    })
final class SecretsCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** {@code jrs-upgrade secrets init}. */
  @Command(
      name = "init",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Create an empty secrets.enc protected by the passphrase.")
  static final class Init implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        EncryptedSecretStore store = boot.secretStore();
        store.init();
        boot.services()
            .stateStore()
            .get()
            .audit(AuditActor.current(), "secrets.init", store.file().toString());
        if (global.json()) {
          JsonOut.print(out, Map.of("file", store.file().toString()));
        } else {
          out.println("initialised " + store.file());
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrs-upgrade secrets set <name> [--from-env VAR | --from-file <path>]} (else stdin). */
  @Command(
      name = "set",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Store a secret under NAME; the value comes from an env var, a file or stdin.")
  static final class Set implements Callable<Integer> {

    /** Where the value comes from; stdin when neither is given. */
    static final class Source {
      @Option(names = "--from-env", paramLabel = "VAR", description = "Read the value from VAR.")
      String fromEnv;

      @Option(
          names = "--from-file",
          paramLabel = "<path>",
          description = "Read the value from a file (trailing newlines ignored).")
      Path fromFile;
    }

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<name>", description = "Entry name (enc:NAME).")
    String name;

    @ArgGroup(exclusive = true)
    Source source;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      char[] value;
      if (source != null && source.fromEnv != null) {
        String v = Env.vars().get(source.fromEnv);
        if (v == null || v.isEmpty()) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "environment variable " + source.fromEnv + " is not set");
        }
        value = v.toCharArray();
      } else if (source != null && source.fromFile != null) {
        try (Reader in = Files.newBufferedReader(source.fromFile, StandardCharsets.UTF_8)) {
          value = readAll(in);
        }
      } else {
        value = fromStdin(name);
      }
      try {
        if (value.length == 0) {
          return ExitCodes.fail(
              out, err, global.json(), ExitCodes.PRECHECK_FAILED, "empty value for " + name);
        }
        try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC());
            Secret secret = Secret.of(value)) {
          boot.secretStore().set(name, secret);
          boot.services().stateStore().get().audit(AuditActor.current(), "secrets.set", name);
          if (global.json()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("ref", "enc:" + name);
            JsonOut.print(out, row);
          } else {
            out.println("stored " + name + "; reference it as enc:" + name);
            out.flush();
          }
          return ExitCodes.SUCCESS;
        }
      } finally {
        Arrays.fill(value, '\0');
      }
    }

    /** Reads the whole reader into chars, dropping trailing line terminators. */
    static char[] readAll(Reader in) throws IOException {
      char[] buf = new char[256];
      int len = 0;
      int c;
      while ((c = in.read()) != -1) {
        if (len == buf.length) {
          char[] bigger = Arrays.copyOf(buf, buf.length * 2);
          Arrays.fill(buf, '\0');
          buf = bigger;
        }
        buf[len++] = (char) c;
      }
      while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
        len--;
      }
      char[] result = Arrays.copyOf(buf, len);
      Arrays.fill(buf, '\0');
      return result;
    }

    /** One line from the console (hidden) or, without a console, from stdin. */
    private static char[] fromStdin(String name) throws IOException {
      Optional<Console> console = Terminal.console();
      if (console.isPresent()) {
        char[] chars = console.get().readPassword("value for %s: ", name);
        return chars == null ? new char[0] : chars;
      }
      BufferedReader in =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      char[] buf = new char[256];
      int len = 0;
      int c;
      while ((c = in.read()) != -1 && c != '\n') {
        if (len == buf.length) {
          char[] bigger = Arrays.copyOf(buf, buf.length * 2);
          Arrays.fill(buf, '\0');
          buf = bigger;
        }
        buf[len++] = (char) c;
      }
      while (len > 0 && buf[len - 1] == '\r') {
        len--;
      }
      char[] result = Arrays.copyOf(buf, len);
      Arrays.fill(buf, '\0');
      return result;
    }
  }

  /** {@code jrs-upgrade secrets remove <name>}. */
  @Command(
      name = "remove",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "Delete an entry; no passphrase needed.")
  static final class Remove implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Parameters(index = "0", paramLabel = "<name>", description = "Entry name.")
    String name;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        if (!boot.secretStore().remove(name)) {
          return ExitCodes.fail(
              out,
              err,
              global.json(),
              ExitCodes.PRECHECK_FAILED,
              "no entry named " + name,
              Optional.of("see `jrs-upgrade secrets list`"));
        }
        boot.services().stateStore().get().audit(AuditActor.current(), "secrets.remove", name);
        if (global.json()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("name", name);
          row.put("removed", true);
          JsonOut.print(out, row);
        } else {
          out.println("removed " + name);
          out.flush();
        }
        return ExitCodes.SUCCESS;
      }
    }
  }

  /** {@code jrs-upgrade secrets list [--json]}: names only. */
  @Command(
      name = "list",
      mixinStandardHelpOptions = true,
      exitCodeOnInvalidInput = ExitCodes.USAGE,
      description = "List entry names; values are never shown.")
  static final class ListSecrets implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
        EncryptedSecretStore store = boot.secretStore();
        List<String> names = store.exists() ? store.list() : List.of();
        if (global.json()) {
          JsonOut.print(out, names);
          return ExitCodes.SUCCESS;
        } else if (!store.exists()) {
          out.println("no secrets store at " + store.file() + "; run `jrs-upgrade secrets init`");
        } else if (names.isEmpty()) {
          out.println("no entries in " + store.file());
        } else {
          for (String n : names) {
            out.println(n);
          }
        }
        out.flush();
        return ExitCodes.SUCCESS;
      }
    }
  }
}
