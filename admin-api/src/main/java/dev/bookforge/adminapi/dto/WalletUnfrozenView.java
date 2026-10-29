package dev.bookforge.adminapi.dto;

import java.util.UUID;

/** Receipt for a staff wallet unfreeze. {@code lifted=false} means the wallet was not frozen. */
public record WalletUnfrozenView(UUID userId, boolean lifted) {}
