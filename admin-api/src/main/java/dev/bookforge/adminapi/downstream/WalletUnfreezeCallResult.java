package dev.bookforge.adminapi.downstream;

/** Billing {@code POST /internal/admin/wallet/unfreeze} outcome. */
public record WalletUnfreezeCallResult(boolean success, boolean lifted) {

    public static WalletUnfreezeCallResult failed() {
        return new WalletUnfreezeCallResult(false, false);
    }

    public static WalletUnfreezeCallResult ok(boolean lifted) {
        return new WalletUnfreezeCallResult(true, lifted);
    }
}
