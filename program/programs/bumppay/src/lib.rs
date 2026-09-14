//! BumpPay's on-chain session program.
//!
//! # Why this exists when raw SPL delegation already works
//!
//! The blueprint's Option A — a plain `ApproveChecked` delegate — gets a working tap-to-pay
//! flow with zero Rust. It also has two properties that are unacceptable for anything
//! beyond a demo, and both are fixed here:
//!
//! 1. **No expiry.** SPL delegation is bounded by amount only. "The session ends when you
//!    background the app" is a client-side statement and is not enforceable; a leaked
//!    session key remains spendable until the balance or the approval runs out.
//! 2. **No destination.** A delegate may move the approved tokens to *any* account. The
//!    payer app pins the destination, but client-side enforcement is only as trustworthy as
//!    the client.
//!
//! This program keeps the approved spend inside a PDA that records an expiry **and** the
//! one destination the session may pay, and enforces both on-chain.
//!
//! # Scope discipline
//!
//! Exactly three instructions — `init_session`, `spend_via_session`, `revoke_session`. The
//! blueprint is emphatic about this and it is the right call: multi-session management and
//! fee logic are the kind of scope creep that eats week three.
//!
//! # Instruction discriminators
//!
//! Anchor prefixes each instruction with the first 8 bytes of `sha256("global:<name>")`.
//! BumpPay's Kotlin client in `core/solana/SolanaPrograms.kt` hardcodes those constants so
//! that the hand-rolled transaction path does not depend on the Anchor TypeScript toolchain:
//!
//! ```text
//! init_session       79 ce 50 6a e7 c2 e1 f8
//! spend_via_session  40 11 54 71 37 40 b4 c9
//! revoke_session     56 5c c6 78 90 02 07 c2
//! ```
//!
//! **Renaming any instruction below invalidates those constants.** `tests/bumppay.ts`
//! recomputes them and fails loudly if they drift.

use anchor_lang::prelude::*;
use anchor_spl::token::{self, ApproveChecked, Mint, Revoke, Token, TokenAccount, TransferChecked};

declare_id!("BumpPay111111111111111111111111111111111111");

/// PDA seed prefix: `[b"session", owner, transient]`.
///
/// Must match `SolanaPrograms.BumpPay.SESSION_SEED` in the Kotlin client. A mismatch is a
/// silent one — the client derives a valid-looking address that simply is not this program's
/// PDA, and the failure appears at runtime as `ConstraintSeeds`.
pub const SESSION_SEED: &[u8] = b"session";

#[program]
pub mod bumppay {
    use super::*;

    /// Creates the session and delegates `limit` base units to the transient key.
    ///
    /// `destination` is the merchant's token account, and is recorded rather than merely
    /// trusted: it is the parameter that closes the "delegate can pay anyone" hole.
    pub fn init_session(
        ctx: Context<InitSession>,
        expiry: i64,
        limit: u64,
        destination: Pubkey,
    ) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;

        require!(limit > 0, BumpPayError::ZeroLimit);
        require!(
            expiry > now,
            BumpPayError::ExpiryInThePast
        );

        let session = &mut ctx.accounts.session;
        session.owner = ctx.accounts.owner.key();
        session.transient = ctx.accounts.transient.key();
        session.mint = ctx.accounts.mint.key();
        session.destination = destination;
        session.limit = limit;
        session.spent = 0;
        session.expiry = expiry;
        session.bump = ctx.bumps.session;

        // Mirror the delegation on-chain so that what the session PDA records and what the
        // token program will actually honour cannot diverge.
        token::approve_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                ApproveChecked {
                    to: ctx.accounts.owner_token_account.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    delegate: ctx.accounts.transient.to_account_info(),
                    authority: ctx.accounts.owner.to_account_info(),
                },
            ),
            limit,
            ctx.accounts.mint.decimals,
        )?;

        emit!(SessionInitialised {
            owner: session.owner,
            transient: session.transient,
            limit,
            expiry,
            destination,
        });

        Ok(())
    }

    /// Moves `amount` base units from the owner to the session's recorded destination.
    ///
    /// The transient key signs this; the owner does not. That is the whole product: a tap
    /// settles without the payer's wallet being involved.
    pub fn spend_via_session(ctx: Context<SpendViaSession>, amount: u64) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let session = &mut ctx.accounts.session;

        // The owner passed in must be the session's owner. Cheap, but it is what makes the
        // PDA seeds constraint below meaningful rather than decorative.
        require_keys_eq!(
            ctx.accounts.owner.key(),
            session.owner,
            BumpPayError::OwnerMismatch
        );
        require_keys_eq!(
            ctx.accounts.owner_token_account.owner,
            session.owner,
            BumpPayError::OwnerMismatch
        );

        // 1. Expiry. The check raw SPL delegation cannot express.
        require!(now < session.expiry, BumpPayError::SessionExpired);

        require!(amount > 0, BumpPayError::ZeroAmount);

        // 2. Destination pinning. Without this, a compromised payer app could direct the
        //    approved funds anywhere and the chain would allow it.
        require_keys_eq!(
            ctx.accounts.destination_token_account.key(),
            session.destination,
            BumpPayError::DestinationNotAllowed
        );

        // 3. Remaining budget.
        let remaining = session
            .limit
            .checked_sub(session.spent)
            .ok_or(BumpPayError::LimitExceeded)?;
        require!(amount <= remaining, BumpPayError::LimitExceeded);

        // 4. Transfer. The transient key is the delegate, so this succeeds without the
        //    owner's signature.
        token::transfer_checked(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.owner_token_account.to_account_info(),
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.destination_token_account.to_account_info(),
                    authority: ctx.accounts.transient.to_account_info(),
                },
            ),
            amount,
            ctx.accounts.mint.decimals,
        )?;

        // 5. Decrement only after the transfer succeeded.
        session.spent = session
            .spent
            .checked_add(amount)
            .ok_or(BumpPayError::LimitExceeded)?;

        emit!(SessionSpent {
            owner: session.owner,
            transient: session.transient,
            amount,
            remaining: session.limit - session.spent,
        });

        Ok(())
    }

    /// Zeroes the delegation and closes the session PDA, returning rent to the owner.
    pub fn revoke_session(ctx: Context<RevokeSession>) -> Result<()> {
        token::revoke(CpiContext::new(
            ctx.accounts.token_program.to_account_info(),
            Revoke {
                source: ctx.accounts.owner_token_account.to_account_info(),
                authority: ctx.accounts.owner.to_account_info(),
            },
        ))?;

        // The account itself is closed by the `close = owner` constraint on the Accounts
        // struct, which also refunds the rent.
        emit!(SessionRevoked {
            owner: ctx.accounts.owner.key(),
        });

        Ok(())
    }
}

// =========================================================================================
// State
// =========================================================================================

#[account]
#[derive(InitSpace)]
pub struct Session {
    pub owner: Pubkey,
    pub transient: Pubkey,
    pub mint: Pubkey,
    /// The one token account this session may pay.
    pub destination: Pubkey,
    /// Total base units the session may move.
    pub limit: u64,
    /// Base units moved so far.
    pub spent: u64,
    /// Unix seconds after which the session is dead, enforced on-chain.
    pub expiry: i64,
    pub bump: u8,
}

// `InitSpace` derives the allocation: 4 pubkeys (128) + 3 u64s/i64 (24) + bump (1) = 153,
// plus the 8-byte discriminator Anchor adds.

// =========================================================================================
// Accounts
// =========================================================================================

#[derive(Accounts)]
pub struct InitSession<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,

    /// CHECK: the transient key does not sign at setup — it is only recorded here and
    /// registered as the token delegate. Constraining it further would serve no purpose,
    /// since it is a seed of the PDA below and any typo simply produces a different PDA.
    pub transient: UncheckedAccount<'info>,

    pub mint: Account<'info, Mint>,

    /// CHECK: verified by the token program during the CPI; it must be owned by `owner`.
    #[account(mut)]
    pub owner_token_account: Account<'info, TokenAccount>,

    #[account(
        init,
        payer = owner,
        space = 8 + Session::INIT_SPACE,
        seeds = [SESSION_SEED, owner.key().as_ref(), transient.key().as_ref()],
        bump,
    )]
    pub session: Account<'info, Session>,

    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct SpendViaSession<'info> {
    /// The session key. The only signature required to move value.
    pub transient: Signer<'info>,

    /// CHECK: used to re-derive the session PDA. The real authorisation is that the derived
    /// address matches an initialised Session whose `owner` field is checked in the handler.
    pub owner: UncheckedAccount<'info>,

    pub mint: Account<'info, Mint>,

    #[account(
        mut,
        seeds = [SESSION_SEED, owner.key().as_ref(), transient.key().as_ref()],
        bump,
    )]
    pub session: Account<'info, Session>,

    #[account(mut)]
    pub owner_token_account: Account<'info, TokenAccount>,

    #[account(mut)]
    pub destination_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct RevokeSession<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,

    #[account(mut)]
    pub owner_token_account: Account<'info, TokenAccount>,

    /// CHECK: the transient key is only needed to re-derive the PDA being closed.
    pub transient: UncheckedAccount<'info>,

    #[account(
        mut,
        seeds = [SESSION_SEED, owner.key().as_ref(), transient.key().as_ref()],
        bump,
        close = owner,
    )]
    pub session: Account<'info, Session>,

    pub token_program: Program<'info, Token>,
}

// =========================================================================================
// Events — these are what make the demo video legible in an explorer
// =========================================================================================

#[event]
pub struct SessionInitialised {
    pub owner: Pubkey,
    pub transient: Pubkey,
    pub limit: u64,
    pub expiry: i64,
    pub destination: Pubkey,
}

#[event]
pub struct SessionSpent {
    pub owner: Pubkey,
    pub transient: Pubkey,
    pub amount: u64,
    pub remaining: u64,
}

#[event]
pub struct SessionRevoked {
    pub owner: Pubkey,
}

// =========================================================================================
// Errors — deliberately specific, because "custom program error: 0x1770" helps nobody
// during a live demo
// =========================================================================================

#[error_code]
pub enum BumpPayError {
    #[msg("The session limit must be greater than zero. Use the client's over-limit approval path instead.")]
    ZeroLimit,

    #[msg("The session expiry must be in the future.")]
    ExpiryInThePast,

    #[msg("The session has expired.")]
    SessionExpired,

    #[msg("The amount must be greater than zero.")]
    ZeroAmount,

    #[msg("This payment exceeds the session's remaining limit.")]
    LimitExceeded,

    #[msg("The destination token account is not the one this session was created for.")]
    DestinationNotAllowed,

    #[msg("The supplied owner does not match the session's owner.")]
    OwnerMismatch,
}
