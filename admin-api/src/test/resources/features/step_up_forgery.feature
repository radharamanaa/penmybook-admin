Feature: Step-up token forgery is rejected
  StepUpVerifier MUST cryptographically verify the HMAC signature, action, and expiry. A token
  that someone constructs without access to the real signing key — or that has been tampered with
  after signing, or whose action segment was swapped, or that has expired, or that is malformed —
  MUST be rejected as step_up_invalid. These scenarios mint forgeries actively (not with the real
  key) so the verifier cannot accidentally accept them.

  Scenario: A token signed with the wrong key is rejected
    When the admin grants credit with a step-up token signed by the wrong key
    Then the action is rejected for invalid step-up

  Scenario: A token whose signature was tampered after signing is rejected
    When the admin grants credit with a step-up token whose signature was tampered
    Then the action is rejected for invalid step-up

  Scenario: A token whose action segment was rewritten after signing is rejected
    When the admin grants credit with a step-up token whose action segment was rewritten from refund
    Then the action is rejected for invalid step-up

  Scenario: An expired token is rejected
    When the admin grants credit with an expired step-up token
    Then the action is rejected for invalid step-up

  Scenario: A malformed token is rejected cleanly
    When the admin grants credit with a malformed step-up token
    Then the action is rejected for invalid step-up

  Scenario: A token with an empty action segment is rejected
    When the admin grants credit with a step-up token whose action segment is empty
    Then the action is rejected for invalid step-up

  Scenario: A token whose params-hash does not match the request body is rejected
    # The signature is valid and the action matches, but the token was minted to authorize a
    # different params body. The hash binding catches the swap (e.g. attacker reuses a 5-credit
    # token to grant 9,999 credits) before any downstream call is issued.
    When the admin grants credit with a token whose params-hash does not match the request body
    Then the action is rejected for invalid step-up
