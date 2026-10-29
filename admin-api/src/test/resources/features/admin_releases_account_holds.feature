Feature: Staff can lift a chargeback or review hold
  A dispute freezes the wallet and suspends the account. Before 28 Oct 2026 no admin route could undo
  either, so a held customer stayed locked out until someone wrote code. Both lifts are platform-admin
  only and step-up (OTP) gated. Reactivation binds the target user; the wallet unfreeze binds
  {ownerType, ownerId} and billing spends the token. Entitlements refuses to "reactivate" an account
  that is not suspended (banned, waitlisted, soft-deleted, active).

  Scenario: A platform admin reads why a user is held
    Given a platform admin is signed in
    And the target user is suspended for review with a frozen wallet and 126 cents unrecovered
    When they read the target user's account state
    Then the response status is 200
    And the account state shows "SUSPENDED_FOR_REVIEW", a frozen wallet for "dispute:du_test" and 126 cents unrecovered

  Scenario: A platform admin reactivates a suspended account
    Given a platform admin is signed in
    When they reactivate the target user with a valid step-up token
    Then the response status is 200
    And the reactivated account status is "ACTIVE"
    And entitlements was asked to unsuspend the target user
    And the audit log has an ALLOW row for action "ACCOUNT_REACTIVATE"

  Scenario: Reactivating an account that is not suspended changes nothing
    Given a platform admin is signed in
    And entitlements reports the target user is "BANNED" and not suspended
    When they reactivate the target user with a valid step-up token
    Then the response status is 409
    And the response code is "NOT_SUSPENDED"

  Scenario: Reactivation without a step-up token is rejected
    Given a platform admin is signed in
    When they reactivate the target user without a step-up token
    Then the response status is 403
    And the response code is "step_up_required"
    And entitlements was not asked to unsuspend anyone

  Scenario: A reactivation token approved for another user is rejected
    Given a platform admin is signed in
    When they reactivate the target user with a token approved for another user
    Then the response status is 403
    And the response code is "step_up_invalid"
    And entitlements was not asked to unsuspend anyone

  Scenario: A non-admin cannot reactivate an account
    Given a non-admin user is signed in
    When they attempt to reactivate the target user
    Then the response status is 403
    And the audit log has a DENY row for action "ACCOUNT_REACTIVATE"

  Scenario: A platform admin lifts a wallet freeze and billing spends the token
    Given a platform admin is signed in
    When they unfreeze the target user's wallet with a valid step-up token
    Then the response status is 200
    And the wallet unfreeze reports lifted
    And billing was asked to unfreeze the target user's USER wallet with the step-up token
    And the audit log has an ALLOW row for action "WALLET_UNFREEZE"

  Scenario: A wallet unfreeze without a step-up token is rejected
    Given a platform admin is signed in
    When they unfreeze the target user's wallet without a step-up token
    Then the response status is 403
    And the response code is "step_up_required"
    And billing was not asked to unfreeze any wallet

  Scenario: A wallet unfreeze token approved for another user is rejected
    Given a platform admin is signed in
    When they unfreeze the target user's wallet with a token approved for another user
    Then the response status is 403
    And the response code is "step_up_invalid"
    And billing was not asked to unfreeze any wallet

  Scenario: admin-api checks a wallet unfreeze token but leaves spending it to billing
    Given a platform admin is signed in
    When they unfreeze the target user's wallet twice with the same step-up token
    Then billing was asked to unfreeze 2 times

  Scenario: Billing refusing the unfreeze token is reported as a failure, not a success
    Given a platform admin is signed in
    And billing refuses the wallet unfreeze
    When they unfreeze the target user's wallet with a valid step-up token
    Then the response status is 502
    And the response code is "DOWNSTREAM_REJECTED"

  Scenario: A reactivation token cannot unfreeze a wallet
    Given a platform admin is signed in
    When they unfreeze the target user's wallet with a reactivation token
    Then the response status is 403
    And the response code is "step_up_invalid"
    And billing was not asked to unfreeze any wallet

  Scenario: A platform admin reads an org wallet's freeze
    Given a platform admin is signed in
    And the target org wallet is frozen for "dispute:du_org" with 300 cents unrecovered
    When they read the target org's wallet state
    Then the response status is 200
    And the org wallet state shows a freeze for "dispute:du_org" and 300 cents unrecovered

  Scenario: A platform admin lifts an org wallet freeze
    Given a platform admin is signed in
    When they unfreeze the target org's wallet with a valid step-up token
    Then the response status is 200
    And the wallet unfreeze reports lifted
    And billing was asked to unfreeze the target org's ORG wallet
    And the audit log has an ALLOW row for action "WALLET_UNFREEZE"

  Scenario: A user-wallet approval cannot lift an org freeze
    Given a platform admin is signed in
    When they unfreeze the target org's wallet with a token approved for a user wallet
    Then the response status is 403
    And the response code is "step_up_invalid"
    And billing was not asked to unfreeze any wallet

  Scenario: A non-admin cannot lift an org wallet freeze
    Given a non-admin user is signed in
    When they attempt to unfreeze the target org's wallet
    Then the response status is 403
    And billing was not asked to unfreeze any wallet

  Scenario: A non-admin cannot lift a wallet freeze
    Given a non-admin user is signed in
    When they attempt to unfreeze the target user's wallet
    Then the response status is 403
    And the audit log has a DENY row for action "WALLET_UNFREEZE"
