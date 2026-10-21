package com.delivery.payment.service;

import com.delivery.payment.dto.AccountResponse;
import com.delivery.payment.dto.CapturePaymentRequest;
import com.delivery.payment.dto.CreateAccountRequest;
import com.delivery.payment.dto.HoldPaymentRequest;
import com.delivery.payment.dto.PaymentTransactionResponse;
import com.delivery.payment.dto.RefundPaymentRequest;
import com.delivery.payment.entity.JournalEntry;
import com.delivery.payment.entity.LedgerAccount;
import com.delivery.payment.exception.AccountNotFoundException;
import com.delivery.payment.exception.InsufficientFundsException;
import com.delivery.payment.exception.InvalidPaymentOperationException;
import com.delivery.payment.repository.JournalEntryRepository;
import com.delivery.payment.repository.LedgerAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final LedgerAccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public PaymentService(
            LedgerAccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository
    ) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Optional<LedgerAccount> existing = accountRepository.findByUserId(request.userId());
        if (existing.isPresent()) {
            log.info("Ledger account already exists for userId {}", request.userId());
            return AccountResponse.from(existing.get());
        }

        String currency = (request.currency() != null && !request.currency().isBlank())
                ? request.currency().toUpperCase()
                : "USD";

        BigDecimal initial = request.initialBalance() != null
                ? request.initialBalance().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        LedgerAccount account = new LedgerAccount(request.userId(), initial, currency);
        LedgerAccount saved = accountRepository.save(account);
        log.info("Created ledger account id={} for userId={} with initial balance={}", saved.getId(), saved.getUserId(), initial);
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountByUserId(UUID userId) {
        LedgerAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("Ledger account not found for userId: " + userId));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID id) {
        LedgerAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Ledger account not found for id: " + id));
        return AccountResponse.from(account);
    }

    @Transactional
    public PaymentTransactionResponse holdPayment(HoldPaymentRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);

        // 1. Check idempotency
        if (request.idempotencyKey() != null) {
            Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                JournalEntry entry = existing.get();
                LedgerAccount account = accountRepository.findById(entry.getSourceAccountId()).orElse(null);
                log.info("Returning idempotent response for key={}, orderId={}", request.idempotencyKey(), request.orderId());
                return PaymentTransactionResponse.success(entry, account, "Idempotent HOLD replayed");
            }
        }

        // 2. Fetch customer account with pessimistic lock
        LedgerAccount account = accountRepository.findByUserIdWithLock(request.userId())
                .orElseThrow(() -> new AccountNotFoundException("Ledger account not found for userId: " + request.userId()));

        // 3. Verify available funds
        BigDecimal available = account.getAvailableBalance();
        if (available.compareTo(amount) < 0) {
            String failureReason = String.format("Insufficient funds: required %s %s, available %s %s",
                    amount, account.getCurrency(), available, account.getCurrency());
            log.warn("HOLD failed for orderId={}, userId={}: {}", request.orderId(), request.userId(), failureReason);

            JournalEntry failedEntry = new JournalEntry(
                    request.orderId(),
                    account.getId(),
                    request.destinationAccountId(),
                    amount,
                    JournalEntry.EntryType.HOLD,
                    JournalEntry.EntryStatus.FAILED,
                    failureReason,
                    request.idempotencyKey()
            );
            journalEntryRepository.save(failedEntry);

            throw new InsufficientFundsException(failureReason);
        }

        // 4. Reserve funds: increment held_balance
        account.setHeldBalance(account.getHeldBalance().add(amount));
        accountRepository.save(account);

        // 5. Record journal entry
        JournalEntry entry = new JournalEntry(
                request.orderId(),
                account.getId(),
                request.destinationAccountId(),
                amount,
                JournalEntry.EntryType.HOLD,
                JournalEntry.EntryStatus.SUCCESS,
                null,
                request.idempotencyKey()
        );
        JournalEntry savedEntry = journalEntryRepository.save(entry);

        log.info("HOLD successful: orderId={}, amount={}, new heldBalance={}, availableBalance={}",
                request.orderId(), amount, account.getHeldBalance(), account.getAvailableBalance());

        return PaymentTransactionResponse.success(savedEntry, account, "HOLD reserved successfully");
    }

    @Transactional
    public PaymentTransactionResponse capturePayment(CapturePaymentRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);

        // 1. Check idempotency
        if (request.idempotencyKey() != null) {
            Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                JournalEntry entry = existing.get();
                LedgerAccount account = accountRepository.findById(entry.getSourceAccountId()).orElse(null);
                log.info("Returning idempotent response for key={}, orderId={}", request.idempotencyKey(), request.orderId());
                return PaymentTransactionResponse.success(entry, account, "Idempotent CAPTURE replayed");
            }
        }

        // 2. Fetch source account with pessimistic lock
        LedgerAccount sourceAccount = accountRepository.findByUserIdWithLock(request.userId())
                .orElseThrow(() -> new AccountNotFoundException("Source ledger account not found for userId: " + request.userId()));

        // 3. Validate held balance availability
        if (sourceAccount.getHeldBalance().compareTo(amount) < 0) {
            String failureReason = String.format("Cannot capture: held balance (%s) is less than capture amount (%s)",
                    sourceAccount.getHeldBalance(), amount);
            log.warn("CAPTURE failed for orderId={}, userId={}: {}", request.orderId(), request.userId(), failureReason);

            JournalEntry failedEntry = new JournalEntry(
                    request.orderId(),
                    sourceAccount.getId(),
                    request.destinationAccountId(),
                    amount,
                    JournalEntry.EntryType.CAPTURE,
                    JournalEntry.EntryStatus.FAILED,
                    failureReason,
                    request.idempotencyKey()
            );
            journalEntryRepository.save(failedEntry);

            throw new InvalidPaymentOperationException(failureReason);
        }

        // 4. Settle funds: deduct held_balance and total balance
        sourceAccount.setHeldBalance(sourceAccount.getHeldBalance().subtract(amount));
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(amount));
        accountRepository.save(sourceAccount);

        // 5. Transfer to destination account if provided (e.g., restaurant merchant)
        if (request.destinationAccountId() != null) {
            Optional<LedgerAccount> destAccountOpt = accountRepository.findByIdWithLock(request.destinationAccountId());
            if (destAccountOpt.isPresent()) {
                LedgerAccount destAccount = destAccountOpt.get();
                destAccount.setBalance(destAccount.getBalance().add(amount));
                accountRepository.save(destAccount);
                log.info("Transferred {} to destination account {}", amount, destAccount.getId());
            } else {
                log.warn("Destination account {} not found during capture; held in escrow", request.destinationAccountId());
            }
        }

        // 6. Record journal entry
        JournalEntry entry = new JournalEntry(
                request.orderId(),
                sourceAccount.getId(),
                request.destinationAccountId(),
                amount,
                JournalEntry.EntryType.CAPTURE,
                JournalEntry.EntryStatus.SUCCESS,
                null,
                request.idempotencyKey()
        );
        JournalEntry savedEntry = journalEntryRepository.save(entry);

        log.info("CAPTURE successful: orderId={}, amount={}, new balance={}, remaining held={}",
                request.orderId(), amount, sourceAccount.getBalance(), sourceAccount.getHeldBalance());

        return PaymentTransactionResponse.success(savedEntry, sourceAccount, "Funds captured successfully");
    }

    @Transactional
    public PaymentTransactionResponse refundPayment(RefundPaymentRequest request) {
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);

        // 1. Check idempotency
        if (request.idempotencyKey() != null) {
            Optional<JournalEntry> existing = journalEntryRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                JournalEntry entry = existing.get();
                LedgerAccount account = accountRepository.findById(entry.getSourceAccountId()).orElse(null);
                log.info("Returning idempotent response for key={}, orderId={}", request.idempotencyKey(), request.orderId());
                return PaymentTransactionResponse.success(entry, account, "Idempotent REFUND replayed");
            }
        }

        // 2. Fetch customer account with pessimistic lock
        LedgerAccount customerAccount = accountRepository.findByUserIdWithLock(request.userId())
                .orElseThrow(() -> new AccountNotFoundException("Customer ledger account not found for userId: " + request.userId()));

        // 3. Inspect previous transactions for this order to determine refund mechanics
        Optional<JournalEntry> captureEntry = journalEntryRepository
                .findFirstByOrderIdAndEntryTypeAndStatus(request.orderId(), JournalEntry.EntryType.CAPTURE, JournalEntry.EntryStatus.SUCCESS);

        UUID destAccountId = null;

        if (captureEntry.isPresent()) {
            // Case A: Order was already captured -> Restore balance to customer and deduct from merchant if available
            customerAccount.setBalance(customerAccount.getBalance().add(amount));
            accountRepository.save(customerAccount);

            destAccountId = captureEntry.get().getDestinationAccountId();
            if (destAccountId != null) {
                Optional<LedgerAccount> merchantOpt = accountRepository.findByIdWithLock(destAccountId);
                if (merchantOpt.isPresent()) {
                    LedgerAccount merchant = merchantOpt.get();
                    if (merchant.getBalance().compareTo(amount) >= 0) {
                        merchant.setBalance(merchant.getBalance().subtract(amount));
                        accountRepository.save(merchant);
                    } else {
                        log.warn("Merchant account {} has insufficient balance ({}) for refund of {}",
                                merchant.getId(), merchant.getBalance(), amount);
                    }
                }
            }
            log.info("Refunded captured orderId={}, restored balance to customer={}", request.orderId(), customerAccount.getBalance());
        } else {
            // Case B: Order was on HOLD (never captured) -> Release the hold
            if (customerAccount.getHeldBalance().compareTo(amount) < 0) {
                // If held balance is smaller, release available held balance
                customerAccount.setHeldBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            } else {
                customerAccount.setHeldBalance(customerAccount.getHeldBalance().subtract(amount));
            }
            accountRepository.save(customerAccount);
            log.info("Released HOLD for orderId={}, remaining heldBalance={}", request.orderId(), customerAccount.getHeldBalance());
        }

        // 4. Record journal entry
        JournalEntry entry = new JournalEntry(
                request.orderId(),
                customerAccount.getId(),
                destAccountId,
                amount,
                JournalEntry.EntryType.REFUND,
                JournalEntry.EntryStatus.SUCCESS,
                request.reason(),
                request.idempotencyKey()
        );
        JournalEntry savedEntry = journalEntryRepository.save(entry);

        return PaymentTransactionResponse.success(savedEntry, customerAccount, "Refund processed successfully");
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> getOrderTransactions(UUID orderId) {
        return journalEntryRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }
}
