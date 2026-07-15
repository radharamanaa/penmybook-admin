Feature: Step-up token single-use (replay protection)
  A valid step-up token may be presented exactly once. Re-presenting it within its TTL is a
  replay and MUST be rejected at admin-api BEFORE any downstream call is issued. The defense
  catches client bugs and stolen-in-flight tokens; entitlements also tracks the underlying OTP
  challenge as a backstop.

  Scenario: Replaying the same valid step-up token within TTL is rejected
    When the admin grants credit with the same step-up token twice
    Then the first grant succeeds and the second is rejected as a replay
