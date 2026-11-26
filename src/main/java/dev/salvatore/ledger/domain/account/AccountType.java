package dev.salvatore.ledger.domain.account;

/**
 * Kind of account in the ledger.
 */
public enum AccountType {

    /** Account owned by a buyer. Balance can never be negative. */
    CUSTOMER,

    /** Account owned by a seller. Balance can never be negative. */
    MERCHANT,

    /** System escrow account holding funds between payment and consolidation (one per currency). */
    TRANSIT,

    /** Models the outside world (e.g. funding source). The only type allowed to go negative. */
    EXTERNAL
}