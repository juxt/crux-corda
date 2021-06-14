package com.example.workflow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUState
import com.example.contract.ItemContract
import com.example.contract.ItemState
import com.example.service.CruxService
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [IOUState].
 *
 * In our simple example, the [Acceptor] always accepts a valid IOU.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object ItemFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val itemValue: Int,
                    val itemName: String) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object VERIFYING_FUNDS : ProgressTracker.Step("Verifying the owner has enough funds")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                VERIFYING_FUNDS,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = VERIFYING_FUNDS
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val crux = serviceHub.cordaService(CruxService::class.java)
            val me = serviceHub.myInfo.legalIdentities.first()
            val currentDb = crux.node.db()

            val borrowed = currentDb.query("""
                    {:find [(sum ?v)] 
                     :in [?b]
                     :where [[?iou :iou-state/borrower ?b]
                             [?iou :iou-state/value ?v]]}
            """.trimIndent(), me.name.toString()).singleOrNull()?.singleOrNull() as Long? ?: 0

            val lent = currentDb.query("""
                    {:find [(sum ?v)] 
                     :in [?l]
                     :where [[?iou :iou-state/lender ?l]
                             [?iou :iou-state/value ?v]]}
            """.trimIndent(), me.name.toString()).singleOrNull()?.singleOrNull() as Long? ?: 0

            val owned = currentDb.query("""
                    {:find [(sum ?v)] 
                     :in [?o]
                     :where [[?item :item/owner ?o]
                             [?item :item/value ?v]]}
            """.trimIndent(), me.name.toString()).singleOrNull()?.singleOrNull() as Long? ?: 0

            val balance = borrowed - lent - owned


            if (balance >= itemValue) {
                val itemState = ItemState(itemName, itemValue, me)
                val txCommand = Command(ItemContract.Commands.Create(), itemState.participants.map { it.owningKey })
                val txBuilder = TransactionBuilder(notary)
                        .addOutputState(itemState, ItemContract.ID)
                        .addCommand(txCommand)
                txBuilder.verify(serviceHub)
                progressTracker.currentStep = SIGNING_TRANSACTION
                // Sign the transaction.
                val signedTx = serviceHub.signInitialTransaction(txBuilder)
                return subFlow(FinalityFlow(transaction = signedTx, sessions = emptySet(), progressTracker = FINALISING_TRANSACTION.childProgressTracker()))
            }

            throw FlowException("Not enough balance!")
        }
    }
}