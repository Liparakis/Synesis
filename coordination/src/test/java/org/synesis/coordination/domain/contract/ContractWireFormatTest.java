package org.synesis.coordination.domain.contract;

import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the persisted bytes of the contract event payloads.
 */
class ContractWireFormatTest {

    @Test
    void publish() throws Exception {
        String body = "contract body Δοκιμή ✓";
        ContractRecord record = new ContractRecord(id(1),
                id(2),
                3,
                "agt-Δοκιμή",
                ContractRecord.hash(body),
                body,
                ContractRecord.Status.ACTIVE,
                id(4),
                List.of("src/a.py", "src/é.py"));
        pin("publish", "53435031123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000000000003000000106167742dce94cebfcebaceb9cebcceae00000040636131313631363163386364306237643661326632323863356234363832343762616163326465333138616432383330323638616163316262316662313738610000001e636f6e747261637420626f647920ce94cebfcebaceb9cebcceae20e29c9301123456789abcdef00fedcba98765430400000002000000087372632f612e7079000000097372632fc3a92e7079", "trailing=CoordinationException: trailing contract bytes | truncated=CoordinationException: truncated text", record, ContractCodec::encodePublish, ContractCodec::decodePublish);
    }

    @Test
    void publishWithoutSupersedes() throws Exception {
        ContractRecord record = ContractRecord.create(id(1), id(2), "agt-owner", "body", List.of("src/a.py"));
        pin("publishFirst", "53435031123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000000000001000000096167742d6f776e6572000000403233306438333538646338653838393062346335386465656236323931326565326632303335376165393261356363383631623938653638666533316163623500000004626f64790000000001000000087372632f612e7079", "trailing=CoordinationException: trailing contract bytes | truncated=CoordinationException: truncated text", record, ContractCodec::encodePublish, ContractCodec::decodePublish);
    }

    @Test
    void dependency() throws Exception {
        ContractDependency dependency = new ContractDependency(id(1),
                "agt-Δοκιμή",
                id(2),
                3,
                ContractDependency.State.ACCEPTED);
        pin("dependency", "53434431123456789abcdef00fedcba987654301000000106167742dce94cebfcebaceb9cebcceae123456789abcdef00fedcba9876543020000000000000003", "trailing=CoordinationException: trailing dependency bytes | truncated=CoordinationException: malformed dependency <- EOFException", dependency, ContractCodec::encodeDependency, ContractCodec::decodeDependency);
    }

    @Test
    void supersede() throws Exception {
        ContractSupersede supersede = new ContractSupersede(id(1), 2, 3);
        pin("supersede",
                "53435331123456789abcdef00fedcba98765430100000000000000020000000000000003",
                "trailing=CoordinationException: trailing supersession bytes | truncated=EOFException: null",
                supersede,
                value -> ContractCodec.encodeSupersede(value.contract().value(),
                        value.previousRevision().value(),
                        value.replacementRevision().value()),
                ContractCodec::decodeSupersede);
    }
}
