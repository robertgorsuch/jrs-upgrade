package com.jaspersoft.jrsupgrade.app;

import java.io.PrintWriter;

/**
 * A yes/no question for the operator. Invariants: the answer is read from the console when there is
 * one, otherwise from stdin, through {@link Prompter}'s one reader; end of input or anything but
 * {@code y}/{@code yes} means no, so an unattended run can never confirm by accident.
 */
final class Confirm {

  private Confirm() {}

  static boolean ask(PrintWriter out, String question) {
    // one reader for every question of a process: a second BufferedReader over the same stdin
    // would find the bytes the first one buffered ahead already gone (ADR-0027 asks two questions
    // in one apply)
    return Prompter.yes(out, question, false);
  }
}
