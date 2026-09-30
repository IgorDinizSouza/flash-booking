package com.flashbooking.repository;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.flashbooking.model.domain.IdempotencyKey;

/** SQL em resources/mapper/IdempotencyRepository.xml. */
@Mapper
public interface IdempotencyRepository {

    /**
     * @return true se a chave foi inserida (venceu a disputa). Sob concorrencia, o INSERT espera a
     *         transacao que detem a mesma chave terminar; false = a chave ja existe (commitada).
     */
    default boolean tryInsert(String key, String requestHash) {
        return insertIfAbsent(key, requestHash) == 1;
    }

    /** INSERT ... ON CONFLICT DO NOTHING: 1 = inseriu, 0 = chave ja existia. */
    int insertIfAbsent(@Param("key") String key, @Param("hash") String requestHash);

    Optional<IdempotencyKey> find(@Param("key") String key);

    void complete(@Param("key") String key, @Param("status") int httpStatus, @Param("json") String responseJson);
}
