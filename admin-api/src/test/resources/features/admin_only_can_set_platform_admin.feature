Feature: Set-platform-admin endpoint is platform-admin only and step-up gated
  PATCH /v1/admin/users/{userId} MUST be gated by hasRole('PLATFORM_ADMIN') and a step-up OTP
  (O-5, 29 Oct 2026) bound to the target user and the new value; a successful call flips the
  boolean and the persisted value is reflected in the response body so the SPA can reconcile
  without a follow-up read.

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to set the target user's is_platform_admin flag to true
    Then the response status is 403
    And the audit log has a DENY row for action "SET_PLATFORM_ADMIN"

  Scenario: A platform admin successfully sets the target user's is_platform_admin flag
    Given a platform admin is signed in
    When they set the target user's is_platform_admin flag to true with a valid step-up token
    Then the response status is 200
    And the response confirms is_platform_admin is now true
    And the audit log has an ALLOW row for action "SET_PLATFORM_ADMIN"

  Scenario: Granting platform admin without a step-up token is rejected
    Given a platform admin is signed in
    When they set the target user's is_platform_admin flag to true
    Then the response status is 403
    And the response code is "step_up_required"
    And entitlements was not asked to change any platform-admin flag

  Scenario: A token approved to revoke platform admin cannot grant it
    Given a platform admin is signed in
    When they set the target user's is_platform_admin flag to true with a token approved for false
    Then the response status is 403
    And the response code is "step_up_invalid"
    And entitlements was not asked to change any platform-admin flag
