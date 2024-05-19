package com.sq018.monieflex.services.providers;

import com.sq018.monieflex.dtos.FLWTransferDto;
import com.sq018.monieflex.dtos.FLWVerifyAccountDto;
import com.sq018.monieflex.dtos.FLWVirtualDto;
import com.sq018.monieflex.dtos.TransferDto;
import com.sq018.monieflex.entities.Wallet;
import com.sq018.monieflex.entities.Transaction;
import com.sq018.monieflex.enums.TransactionStatus;
import com.sq018.monieflex.exceptions.MonieFlexException;
import com.sq018.monieflex.payloads.ApiResponse;
import com.sq018.monieflex.payloads.flutterwave.FLWTransferResponse;
import com.sq018.monieflex.payloads.flutterwave.FLWVerifyAccountResponse;
import com.sq018.monieflex.payloads.flutterwave.FLWVirtualAccountResponse;
import com.sq018.monieflex.payloads.flutterwave.VerifyAccountResponse;
import com.sq018.monieflex.payloads.flutterwave.VirtualAccountResponse;
import com.sq018.monieflex.payloads.flutterwave.AllBanksData;
import com.sq018.monieflex.payloads.flutterwave.FLWAllBanksResponse;
import com.sq018.monieflex.utils.FlutterwaveEndpoints;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class FlutterwaveService {
    @Value("${monieFlex.flutterwave.secret-key}")
    private String FLW_SECRET_KEY;
    private final RestTemplate rest;

    public FlutterwaveService(RestTemplate rest) {
        this.rest = rest;
    }

    public HttpHeaders getFlutterwaveHeader(){
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + FLW_SECRET_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public Wallet createWallet(
            String emailAddress, String BVN, String txRef,
            String lastName, String firstName, String phoneNumber
    ) {
        FLWVirtualDto body = new FLWVirtualDto(
                emailAddress, BVN, firstName, lastName,
                phoneNumber, 1, true, txRef
        );
        HttpEntity<FLWVirtualDto> data = new HttpEntity<>(body, getFlutterwaveHeader());
        try {
            var response = rest.postForEntity(FlutterwaveEndpoints.VIRTUAL_ACCOUNT_NUMBER, data, FLWVirtualAccountResponse.class);

            if(response.getStatusCode().is2xxSuccessful()) {
                if(Objects.requireNonNull(response.getBody()).getStatus().equalsIgnoreCase("success")) {
                    VirtualAccountResponse accountResponse = response.getBody().getData();
                    if(ObjectUtils.isNotEmpty(accountResponse)) {
                        Wallet wallet = new Wallet();
                        wallet.setBalance(BigDecimal.valueOf(0.00));
                        wallet.setReference(accountResponse.getFlwRef());
                        wallet.setBankName(accountResponse.getBankName());
                        wallet.setNumber(accountResponse.getAccountNumber());
                        return wallet;
                    }
                }
                throw new MonieFlexException("Couldn't finish processing data");
            } else {
                throw new MonieFlexException("Error in creating wallet");
            }
        } catch (RestClientException | MonieFlexException e) {
            throw new MonieFlexException("Error in creating wallet, please try again later");
        }

    }

    @SneakyThrows
    public ApiResponse<List<AllBanksData>> getAllBanks() {

        HttpEntity<Object> entity = new HttpEntity<>(getFlutterwaveHeader());
        ResponseEntity<FLWAllBanksResponse> response = rest.exchange(
                FlutterwaveEndpoints.GET_ALL_BANKS,
                HttpMethod.GET, entity, FLWAllBanksResponse.class
        );
        log.info("::::::::::::::flutterwave all banks response: {}", response.getBody());
        if (response.getStatusCode().is2xxSuccessful()){
            FLWAllBanksResponse flwAllBanksResponse = response.getBody();
            if (Objects.requireNonNull(flwAllBanksResponse).getStatus().equalsIgnoreCase("success")){
                List<AllBanksData> allBanksData = flwAllBanksResponse.getData();
                if (ObjectUtils.isNotEmpty(allBanksData)){
                    return new ApiResponse<>("Request Process Successfully", HttpStatus.OK, 200, allBanksData);
                }
            }
            return new ApiResponse<>("Unable to process this request at this moment", HttpStatus.BAD_REQUEST, 400);
        }
        return new ApiResponse<>("Unable to process this request at this moment", HttpStatus.BAD_REQUEST, 400);
    }

    public Transaction bankTransfer(TransferDto transfer, String reference) {
        FLWTransferDto body = new FLWTransferDto(
                transfer.bankCode(),
                transfer.accountNumber(),
                transfer.amount(),
                transfer.narration(),
                "NGN", reference
        );
        HttpEntity<FLWTransferDto> entity = new HttpEntity<>(body, getFlutterwaveHeader());
        var response = rest.postForEntity(FlutterwaveEndpoints.TRANSFER, entity, FLWTransferResponse.class);
        if(response.getStatusCode().is2xxSuccessful()) {
            var responseBody = response.getBody();
            if(Objects.requireNonNull(responseBody).getStatus().equalsIgnoreCase("success")) {
                var data = responseBody.getData();
                if(ObjectUtils.isNotEmpty(data)) {
                    return getTransaction(data.getReference(), TransactionStatus.SUCCESSFUL);
                } else {
                    return getTransaction(reference, TransactionStatus.PENDING);
                }
            } else {
                return getTransaction(reference, TransactionStatus.FAILED);
            }
        } else {
            return getTransaction(reference, TransactionStatus.FAILED);
        }
    }

    private Transaction getTransaction(String reference, TransactionStatus status) {
        Transaction transaction = new Transaction();
        transaction.setReference(reference);
        transaction.setStatus(status);
        return transaction;
    }

    public ApiResponse<VerifyAccountResponse> verifyBankAccount(FLWVerifyAccountDto verifyAccountDto) {
        HttpEntity<FLWVerifyAccountDto> entity = new HttpEntity<>(verifyAccountDto, getFlutterwaveHeader());
        try {
            var request = rest.postForEntity(
                    FlutterwaveEndpoints.VERIFY_BANK_ACCOUNT,
                    entity, FLWVerifyAccountResponse.class
            );
            if(request.getStatusCode().is2xxSuccessful()) {
                FLWVerifyAccountResponse body = request.getBody();
                if(Objects.requireNonNull(body).getStatus().equalsIgnoreCase("success")) {
                    VerifyAccountResponse data = body.getData();
                    if(ObjectUtils.isNotEmpty(data)) {
                        return new ApiResponse<>(
                                data,
                                "Request successfully processed"
                        );
                    }
                }
            }
            throw new MonieFlexException("Error in processing request");
        } catch (Exception e) {
            throw new MonieFlexException("Invalid Account. Please check your details");
        }
    }
}
