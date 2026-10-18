package service

import model.Brand
import model.BrandTable
import util.Identity
import util.runTransactionCatching

/** Operator authentication. Resolves a Brand by its identity and verifies the secret. */
class BrandService {

    /** Returns the brand if (identity, secret) match an operator; null on auth failure. */
    suspend fun authenticate(identity: Identity, secret: String): Result<Brand?> =
        runTransactionCatching {
            Brand.find { BrandTable.identity eq identity.value }
                .firstOrNull()
                ?.takeIf { it.secret == secret }
        }
}
