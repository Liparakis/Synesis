package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Root Picocli command; no arguments prints help.
 */
@Command(name = "synesis", mixinStandardHelpOptions = true, versionProvider = RootCommand.class,
    description = "Coordinate people and coding agents working on one Git project.",
    footer = {"", "Run 'synesis <command> --help' for details on a command."})
public final class RootCommand implements Runnable, IVersionProvider {

  @Spec
  private CommandSpec spec;

  // Declared here so every command accepts and documents the flag; the output mode itself is
  // chosen before parsing, because it decides which terminal the commands are given.
  @Option(names = "--porcelain", scope = ScopeType.INHERIT,
      description = "Print stable KEY=VALUE lines for scripts. This is already the default when output is not a terminal.")
  private boolean porcelain;

  /**
   * Creates the root command.
   */
  public RootCommand() {
  }

  /**
   * Prints root help for an empty invocation.
   */
  @Override
  public void run() {
    spec.commandLine()
        .usage(spec.commandLine()
            .getOut());
  }

  @Override
  public String[] getVersion() {
    return new String[]{"synesis " + BuildInfo.version()};
  }
}
