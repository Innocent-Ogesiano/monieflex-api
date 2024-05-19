package com.sq018.monieflex.services;

import com.sq018.monieflex.dtos.*;

import com.sq018.monieflex.entities.User;
import com.sq018.monieflex.entities.Wallet;
import com.sq018.monieflex.entities.Transaction;
import com.sq018.monieflex.entities.transactions.VerifyFundWallet;
import com.sq018.monieflex.enums.TransactionStatus;
import com.sq018.monieflex.enums.TransactionType;
import com.sq018.monieflex.exceptions.MonieFlexException;
import com.sq018.monieflex.payloads.*;
import com.sq018.monieflex.payloads.flutterwave.VerifyAccountResponse;
import com.sq018.monieflex.payloads.flutterwave.AllBanksData;
import com.sq018.monieflex.repositories.TransactionRepository;
import com.sq018.monieflex.repositories.UserRepository;
import com.sq018.monieflex.repositories.VerifyFundWalletRepository;
import com.sq018.monieflex.repositories.WalletRepository;
import com.sq018.monieflex.services.implementations.EmailImplementation;
import com.sq018.monieflex.services.providers.FlutterwaveService;
import com.sq018.monieflex.utils.TimeUtils;
import com.sq018.monieflex.utils.CreditCardUtil;
import com.sq018.monieflex.utils.OtpEmailTemplate;
import com.sq018.monieflex.utils.UserUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.sq018.monieflex.utils.CreditCardUtil.verify;
import static com.sq018.monieflex.utils.UserUtil.getLoginUser;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final FlutterwaveService flutterwaveService;
    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final UserUtil userUtil;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailImplementation emailImplementation;

    private static final BigDecimal MINIMUM_FUND_AMOUNT = new BigDecimal("100.0");
    private final VerifyFundWalletRepository verifyFundWalletRepository;

    protected String generateTxRef() {
        return "MONF-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public Wallet create(User user) {
        Wallet wallet = flutterwaveService.createWallet(
                user.getEmailAddress(), user.getBvn(), generateTxRef(),
                user.getLastName(), user.getFirstName(), user.getPhoneNumber()
        );
        wallet.setUser(user);
        return wallet;
    }

    public ApiResponse<String> transferToBank(TransferDto transfer) {
        String loginUserEmail = getLoginUser();
        User user = userRepository.findByEmailAddress(loginUserEmail).orElseThrow();
        if(userUtil.isBalanceSufficient(BigDecimal.valueOf(transfer.amount()))) {
            Transaction transaction = new Transaction();
            transaction.setAccount(transfer.accountNumber());
            transaction.setNarration(transfer.narration());
            transaction.setAmount(BigDecimal.valueOf(transfer.amount()));
            transaction.setReference(generateTxRef());
            transaction.setTransactionType(TransactionType.EXTERNAL);
            transaction.setStatus(TransactionStatus.PENDING);
            transaction.setReceivingBankName(transfer.bankName());
            transaction.setReceiverName(transfer.receiverName());
            transaction.setUser(user);
            transactionRepository.save(transaction);

            var result = flutterwaveService.bankTransfer(transfer, transaction.getReference());
            if(result.getStatus() == TransactionStatus.SUCCESSFUL) {
                transaction.setStatus(TransactionStatus.SUCCESSFUL);
                transactionRepository.save(transaction);
                userUtil.updateWalletBalance(BigDecimal.valueOf(transfer.amount()), true);
                return new ApiResponse<>("Transaction successful", HttpStatus.OK);
            } else {
                transaction.setStatus(TransactionStatus.FAILED);
                transactionRepository.save(transaction);
                userUtil.updateWalletBalance(BigDecimal.valueOf(transfer.amount()), false);
                return new ApiResponse<>("Transaction failed", HttpStatus.BAD_REQUEST);
            }
        } else {
            throw new MonieFlexException("Insufficient Balance");
        }
    }

    public ApiResponse<String> localTransfer(LocalTransferRequest localTransferRequest){
        String loginUserEmail = getLoginUser();
        User user = userRepository.findByEmailAddress(loginUserEmail).orElseThrow(
                () -> new MonieFlexException("User not found")
        );
        if (!userUtil.isBalanceSufficient(localTransferRequest.getAmount())){
            return new ApiResponse<>("Insufficient Balance", HttpStatus.BAD_REQUEST);
        }

        Transaction transaction = new Transaction();
        transaction.setAccount(localTransferRequest.getAccountNumber());
        transaction.setNarration(localTransferRequest.getNarration());
        transaction.setAmount(localTransferRequest.getAmount());
        transaction.setReference(generateTxRef());
        transaction.setReceiverName(localTransferRequest.getReceiverName());
        transaction.setTransactionType(TransactionType.LOCAL);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setReceivingBankName("MonieFlex");
        transaction.setUser(user);
        transactionRepository.save(transaction);

        var wallet = walletRepository.findByNumber(localTransferRequest.getAccountNumber());
        if (wallet.isPresent()){
            userUtil.updateWalletBalance(localTransferRequest.getAmount(), true);
            wallet.get().setBalance(wallet.get().getBalance().add(localTransferRequest.getAmount()));
            transaction.setStatus(TransactionStatus.SUCCESSFUL);
            transactionRepository.save(transaction);
            return new ApiResponse<>("Transfer Successful", HttpStatus.OK);
        } else {
            transaction.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(transaction);
            return new ApiResponse<>("Transaction failed", HttpStatus.BAD_REQUEST);
        }
    }

    public ApiResponse<List<AllBanksData>> getAllBanks(){
        return flutterwaveService.getAllBanks();
    }

    public ApiResponse<VerifyAccountResponse> verifyBankAccount(FLWVerifyAccountDto accountDto) {
        return flutterwaveService.verifyBankAccount(accountDto);
    }

    public ApiResponse<LocalAccountQueryResponse> queryLocalAccount(LocalAccountQueryRequest localAccountQueryRequest){
        var wallet = walletRepository.findByNumber(localAccountQueryRequest.getAccount())
                .orElseThrow(() -> new MonieFlexException("Invalid Account"));
        var userWallet = walletRepository.findByUser_EmailAddressIgnoreCase(getLoginUser())
                .orElseThrow(() -> new MonieFlexException("User not found"));

        if(localAccountQueryRequest.getAccount().equals(userWallet.getNumber())) {
            throw new MonieFlexException("You cannot transfer money from your wallet to your wallet");
        }

        var user = wallet.getUser();
        LocalAccountQueryResponse localAccountQueryResponse = new LocalAccountQueryResponse();
        localAccountQueryResponse.setName(user.getFirstName() + " " + user.getLastName());
        return new ApiResponse<>(localAccountQueryResponse, "Success", HttpStatus.OK);
    }

    public ApiResponse<TransactionHistoryResponse> queryHistory(Integer page, Integer size) {
        var user = walletRepository.findByUser_EmailAddressIgnoreCase(getLoginUser())
                .orElseThrow(() -> new MonieFlexException("User not found"));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        var transactions = transactionRepository.findByUser_EmailAddressOrAccount(
                getLoginUser(), user.getNumber(), pageable
        );
        List<TransactionHistory> history = new ArrayList<>();
        transactions.forEach(transaction -> history.add(prepareTransactionHistory(transaction)));

        TransactionHistoryResponse response = new TransactionHistoryResponse();
        response.setData(history);
        response.setPages(transactions.getTotalPages());
        response.setElements(transactions.getTotalElements());
        return new ApiResponse<>(response, "Transaction History successfully fetched");
    }

    private String getName(Transaction transaction) {
        if(transaction.getTransactionType() == TransactionType.AIRTIME
            || transaction.getTransactionType() == TransactionType.DATA
        ) {
            return transaction.getAccount();
        } else if(transaction.getTransactionType() == TransactionType.TV) {
            return transaction.getAccount();
        } else if(transaction.getTransactionType() == TransactionType.ELECTRICITY) {
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            String date = transaction.getCreatedAt().format(timeFormatter);
            return date + "/" + transaction.getAccount().substring(transaction.getAccount().length() - 5);
        } else {
            return transaction.getReceiverName();
        }
    }

    private String getDescription(Transaction transaction) {
        if(transaction.getTransactionType() == TransactionType.AIRTIME) {
            return transaction.getReceiverName();
        } else if(transaction.getTransactionType() == TransactionType.TV) {
            return transaction.getBillType() + " - " + transaction.getBillVariation();
        } else if(transaction.getTransactionType() == TransactionType.ELECTRICITY) {
            return transaction.getBillType().name().toUpperCase();
        } else if(transaction.getTransactionType() == TransactionType.DATA) {
            return transaction.getBillVariation().toUpperCase();
        } else if(transaction.getTransactionType() == TransactionType.LOCAL
            || transaction.getTransactionType() == TransactionType.EXTERNAL
        ) {
            return transaction.getAccount();
        } else {
            return transaction.getNarration();
        }
    }

    public static String getTime(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mma");
        return dateTime.format(formatter);
    }

    public static String getDate(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return dateTime.format(formatter);
    }

    public static String getAmount(BigDecimal n) {
        return NumberFormat.getCurrencyInstance().format(n);
    }

    private TransactionHistory prepareTransactionHistory(Transaction transaction) {
        String loginUserEmail = getLoginUser();
        Wallet user = walletRepository.findByUser_EmailAddressIgnoreCase(loginUserEmail)
                .orElseThrow(() -> new MonieFlexException("User not found"));

        TransactionHistory response = new TransactionHistory();
        response.setName(getName(transaction));
        response.setDescription(getDescription(transaction));
        response.setTime(getTime(transaction.getCreatedAt()));
        response.setDate(getDate(transaction.getCreatedAt()));
        response.setAmount(getAmount(transaction.getAmount()));
        response.setIsCredit(user.getNumber().equals(transaction.getAccount()));
        response.setStatus(transaction.getStatus());
        return response;
    }

    public ApiResponse<List<TransactionHistory>> queryHistory(Integer page, Integer size, TransactionType type) {
        String email = getLoginUser();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        var transactions = transactionRepository.findByTransactionTypeAndUser_EmailAddress(type, email, pageable);
        List<TransactionHistory> history = new ArrayList<>();
        transactions.forEach(transaction -> history.add(prepareTransactionHistory(transaction)));
        return new ApiResponse<>(history, "Transaction History successfully fetched");
    }

    public ApiResponse<WalletPayload> queryWalletDetails() {
        String email = getLoginUser();
        var wallet = walletRepository.findByUser_EmailAddressIgnoreCase(email).orElseThrow(
                () -> new MonieFlexException("Invalid user id")
        );
        WalletPayload payload = new WalletPayload();
        payload.setBalance(wallet.getBalance());
        payload.setNumber(wallet.getNumber());
        payload.setBankName(wallet.getBankName());

        return new ApiResponse<>(payload, "Wallet successfully fetched");
    }

    public ApiResponse<String> createTransactionPin(String pin) {
        String email = getLoginUser();
        var user = userRepository.findByEmailAddress(email).orElseThrow(
                () -> new MonieFlexException("User not found")
        );
        if(pin.length() < 4) {
            throw new MonieFlexException("Pin cannot be less than 4");
        }
        if(pin.length() > 4) {
            throw new MonieFlexException("Pin cannot be more than 4");
        }
        if(user.getTransactionPin() != null) {
            throw new MonieFlexException("You already have a transaction pin.");
        }
        user.setTransactionPin(passwordEncoder.encode(pin));
        userRepository.save(user);
        return new ApiResponse<>("Pin saved successfully", HttpStatus.OK);
    }

    public ApiResponse<String> verifyPin(String pin) {
        String email = getLoginUser();
        var user = userRepository.findByEmailAddress(email).orElseThrow(
                () -> new MonieFlexException("User not found")
        );
        if(user.getTransactionPin() == null) {
            throw new MonieFlexException("You have not created a pin yet");
        } else {
            if(passwordEncoder.matches(pin, user.getTransactionPin())) {
                return new ApiResponse<>("Pin matches", HttpStatus.OK);
            } else {
                throw new MonieFlexException("Pin does not match");
            }
        }
    }

    public ApiResponse<TransactionDataResponse> getTransactionChart() {
        Wallet wallet = walletRepository.findByUser_EmailAddressIgnoreCase(getLoginUser())
                .orElseThrow(() -> new MonieFlexException("Wallet not found"));
        var transactions = transactionRepository.queryByUser_EmailAddressOrAccount(getLoginUser(), wallet.getNumber());
        var months = TimeUtils.getMonths();

        List<Transaction> month1 = new ArrayList<>();
        List<Transaction> month2 = new ArrayList<>();
        List<Transaction> month3 = new ArrayList<>();
        List<Transaction> month4 = new ArrayList<>();
        List<Transaction> month5 = new ArrayList<>();
        List<Transaction> month6 = new ArrayList<>();
        List<Transaction> month7 = new ArrayList<>();
        List<Transaction> month8 = new ArrayList<>();

        List<TransactionData> list = new ArrayList<>();
        transactions.stream()
                .filter(transaction -> transaction.getStatus() == TransactionStatus.SUCCESSFUL)
                .forEach(transaction -> {
                    if(transaction.getCreatedAt().getMonth().name().equals(months.get(0))) {
                        month1.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(1))) {
                        month2.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(2))) {
                        month3.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(3))) {
                        month4.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(4))) {
                        month5.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(5))) {
                        month6.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(6))) {
                        month7.add(transaction);
                    } else if(transaction.getCreatedAt().getMonth().name().equals(months.get(7))) {
                        month8.add(transaction);
                    }
                });

        list.add(prepareChart(month1, months.get(0)));
        list.add(prepareChart(month2, months.get(1)));
        list.add(prepareChart(month3, months.get(2)));
        list.add(prepareChart(month4, months.get(3)));
        list.add(prepareChart(month5, months.get(4)));
        list.add(prepareChart(month6, months.get(5)));
        list.add(prepareChart(month7, months.get(6)));
        list.add(prepareChart(month8, months.get(7)));


        List<BigDecimal> incomes = new ArrayList<>();
        List<BigDecimal> expenses = new ArrayList<>();
        transactions.forEach(transaction -> {
            if(wallet.getNumber().equals(transaction.getAccount())) {
                incomes.add(transaction.getAmount());
            } else {
                expenses.add(transaction.getAmount());
            }
        });

        BigDecimal totalIncome = computeTotal(incomes);
        BigDecimal totalExpense = computeTotal(expenses);
        TransactionDataResponse response = new TransactionDataResponse();
        response.setTotalIncome(totalIncome);
        response.setTotalExpense(totalExpense);
        response.setDataList(list);

        return new ApiResponse<>(response, "Data fetched successfully", HttpStatus.OK);
    }

    private TransactionData prepareChart(List<Transaction> transactions, String month) {
        Wallet user = walletRepository.findByUser_EmailAddressIgnoreCase(getLoginUser())
                .orElseThrow(() -> new MonieFlexException("User not found"));

        List<BigDecimal> incomeList = new ArrayList<>();
        List<BigDecimal> expenseList = new ArrayList<>();

        transactions.forEach(transaction -> {
            var isCredit = user.getNumber().equals(transaction.getAccount());
            if(isCredit) {
                incomeList.add(transaction.getAmount());
            } else {
                expenseList.add(transaction.getAmount());
            }
        });

        var income = calculateExpenditure(incomeList);
        var expense = calculateExpenditure(expenseList);
        TransactionData response = new TransactionData();
        response.setIncome(income);
        response.setExpense(expense);
        response.setMonth(month.substring(0, 3));
        return response;
    }

    private BigDecimal computeTotal(List<BigDecimal> amounts){
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : amounts) {
            total = total.add(value);
        }
        return total;
    }

    private String calculateExpenditure(List<BigDecimal> list) {
        double total = 0.0;

        for (BigDecimal bigDecimal : list) {
            total += Double.parseDouble(String.valueOf(bigDecimal));
        }
        return String.valueOf(total);
    }

    public ApiResponse<String> fundWallet(FundWalletDto fundWalletDto) {
        var card = verify(() -> fundWalletDto).orElseThrow();

        if (card.getAmount().compareTo(MINIMUM_FUND_AMOUNT) < 0) {
            throw new MonieFlexException("Amount to fund must be at least " + MINIMUM_FUND_AMOUNT);
        }

        String email = getLoginUser();
        var user = userRepository.findByEmailAddress(email).orElseThrow(
                () -> new MonieFlexException("User not found")
        );
        var wallet = walletRepository.findByUser_EmailAddressIgnoreCase(email).orElseThrow(
                () -> new MonieFlexException("User not found")
        );

        StringBuilder OTP = new StringBuilder(6);
        for(int i = 0; i < 6; i++) {
            String CHARACTERS = "0123456789";
            SecureRandom random = new SecureRandom();
            int index = random.nextInt(CHARACTERS.length());
            OTP.append(CHARACTERS.charAt(index));
        }

        Transaction transaction = new Transaction();
        transaction.setAmount(card.getAmount());
        transaction.setUser(user);
        transaction.setAccount(wallet.getNumber());
        transaction.setNarration("Fund Wallet");
        transaction.setReceiverName(user.getFirstName() + " " + user.getLastName());
        transaction.setTransactionType(TransactionType.LOCAL);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setReceivingBankName("MonieFlex");
        transaction.setReference(card.getCardName());
        transaction.setReference(CreditCardUtil.mask(card.getCardNumber()));
        var saved = transactionRepository.save(transaction);

        VerifyFundWallet verifyFundWallet = new VerifyFundWallet();
        verifyFundWallet.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        verifyFundWallet.setOtp(passwordEncoder.encode(OTP));
        verifyFundWallet.setUser(user);
        verifyFundWallet.setTransaction(saved);
        verifyFundWallet.setIsUsed(false);
        verifyFundWalletRepository.save(verifyFundWallet);

        emailImplementation.sendEmail(
                OtpEmailTemplate.email(user.getFirstName(), OTP.toString()),
                "Verify your card transaction",
                user.getEmailAddress()
        );
        return new ApiResponse<>("Check your email to verify transaction", HttpStatus.OK);
    }

    public ApiResponse<String> verifyFundWallet(String OTP) {
         var result = verifyFundWalletRepository
                 .findByIsUsedAndUser_EmailAddress(false, getLoginUser());
         if(result.isEmpty()) {
             throw new MonieFlexException("Request cannot be completed");
         } else {
             var response = result.stream().filter(verify -> passwordEncoder.matches(OTP, verify.getOtp()))
                     .findAny().orElseThrow(() -> new MonieFlexException("OTP is not correct"));
             if(LocalDateTime.now().isAfter(response.getExpiresAt())) {
                 throw new MonieFlexException("OTP expired. Please try again");
             } else {
                 response.setIsUsed(true);
                 verifyFundWalletRepository.save(response);
                 transactionRepository.findById(response.getTransaction().getId()).ifPresentOrElse(
                         transaction -> {
                             userUtil.updateWalletBalance(transaction.getAmount(), false);
                             transaction.setStatus(TransactionStatus.SUCCESSFUL);
                             transaction.setUpdatedAt(LocalDateTime.now());
                             transactionRepository.save(transaction);
                         }, () -> {
                             throw new MonieFlexException("Error occurred while completing request");
                         });
             }
         }
        return new ApiResponse<>("Transaction successful", HttpStatus.OK);
    }
}
