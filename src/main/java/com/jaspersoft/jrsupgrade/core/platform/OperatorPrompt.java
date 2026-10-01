package com.jaspersoft.jrsupgrade.core.platform;

/**
 * Channel through which {@link ManualServiceController} asks the human operator to stop or start
 * the application server themselves (spec §5.3, {@code service.kind: manual}). Invariant: {@link
 * #instruct} is only ever called when {@link #interactive()} is true, so a non-interactive session
 * (e.g. {@code --json} automation) never blocks waiting for a person.
 */
public interface OperatorPrompt {

  /** Shows {@code message} to the operator; returns when the message has been delivered. */
  void instruct(String message);

  /** True when a human can act on instructions during this run. */
  boolean interactive();

  /** A prompt for unattended sessions: never interactive, instructions are discarded. */
  static OperatorPrompt nonInteractive() {
    return new OperatorPrompt() {
      @Override
      public void instruct(String message) {
        // nobody is listening
      }

      @Override
      public boolean interactive() {
        return false;
      }
    };
  }
}
