Feature: Set-platform-admin endpoint is platform-admin only
  PATCH /v1/admin/users/{userId} MUST be gated by hasRole('PLATFORM_ADMIN'); a successful
  call flips the boolean and the persisted value is reflected in the response body so the
  SPA can reconcile without a follow-up read.

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to set the target user's is_platform_admin flag to true
    Then the response status is 403
    And the audit log has a DENY row for action "SET_PLATFORM_ADMIN"

  Scenario: A platform admin successfully sets the target user's is_platform_admin flag
    Given a platform admin is signed in
    When they set the target user's is_platform_admin flag to true
    Then the response status is 200
    And the response confirms is_platform_admin is now true
    And the audit log has an ALLOW row for action "SET_PLATFORM_ADMIN"
