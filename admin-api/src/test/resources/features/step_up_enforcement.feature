Feature: Step-up OTP enforcement on admin writes
  Every admin write requires a valid, action-bound step-up token.

  Scenario: A write without a step-up token is rejected
    When the admin grants credit without a step-up token
    Then the action is rejected for missing step-up

  Scenario: A write with a valid step-up token proceeds
    When the admin grants credit with a valid step-up token
    Then the action succeeds

  Scenario: A step-up token bound to one action is rejected for another
    Given a step-up token issued for action "refund"
    When the admin uses it to grant credit
    Then the action is rejected for invalid step-up
