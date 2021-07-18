package com.project.ti2358.data.manager

import com.project.ti2358.data.alor.model.AlorExchange
import com.project.ti2358.data.alor.model.AlorOrder
import com.project.ti2358.data.alor.service.AlorOrdersService
import com.project.ti2358.data.common.BaseOrder
import com.project.ti2358.data.common.BasePosition
import com.project.ti2358.data.tinkoff.model.OperationType
import com.project.ti2358.data.tinkoff.model.TinkoffOrder
import com.project.ti2358.data.tinkoff.service.OrdersService
import com.project.ti2358.service.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.Exception

@KoinApiExtension
class BrokerManager() : KoinComponent {
    private val stockManager: StockManager by inject()
    private val portfolioManager: PortfolioManager by inject()
    private val alorPortfolioManager: AlorPortfolioManager by inject()
    private val ordersService: OrdersService by inject()
    private val alorOrdersService: AlorOrdersService by inject()

    /******************** place order *************************/
    suspend fun placeOrder(stock: Stock, price: Double, lots: Int, operationType: OperationType, refresh: Boolean = false) {
        if (SettingsManager.getBrokerTinkoff()) {
            placeOrderTinkoff(stock, price, lots, operationType)

            if (refresh) portfolioManager.refreshOrders()
        }

        if (SettingsManager.getBrokerAlor()) {
            placeOrderAlor(stock, price, lots, operationType)

            if (refresh) alorPortfolioManager.refreshOrders()
        }
    }

    suspend fun placeOrderTinkoff(stock: Stock, price: Double, count: Int, operationType: OperationType): TinkoffOrder? {
        val operation = if (operationType == OperationType.BUY) "ПОКУПКА!" else "ПРОДАЖА!"
        var order: TinkoffOrder? = null
        try {
            order = ordersService.placeLimitOrder(
                count,
                stock.figi,
                price,
                operationType,
                portfolioManager.getActiveBrokerAccountId()
            )
            Utils.showToastAlert("ТИ ${stock.ticker} новый ордер: $operation")
        } catch (e: Exception) {

        }

        Utils.showToastAlert("ТИ ${stock.ticker} ошибка ордера: $operation")
        return order
    }

    suspend fun placeOrderAlor(stock: Stock, price: Double, lots: Int, operationType: OperationType): String {
        val operation = if (operationType == OperationType.BUY) "ПОКУПКА!" else "ПРОДАЖА!"

        try {
            val response = alorOrdersService.placeLimitOrder(
                operationType,
                lots,
                price,
                stock.ticker,
                AlorExchange.SPBX
            )
            if (response.message == "success") {
                Utils.showToastAlert("ALOR ${stock.ticker} новый ордер: $operation")
                return response.orderNumber ?: ""
            }
        } catch (e: Exception) {

        }

        Utils.showToastAlert("ALOR ${stock.ticker} ошибка ордера: $operation")
        return ""
    }

    /******************** cancel order *************************/
    suspend fun cancelOrder(order: BaseOrder?, refresh: Boolean = false) {
        if (order == null) return

        if (order is TinkoffOrder) {
            cancelOrderTinkoff(order)

            if (refresh) portfolioManager.refreshOrders()
        }

        if (order is AlorOrder) {
            cancelOrderAlor(order)

            if (refresh) alorPortfolioManager.refreshOrders()
        }
    }

    suspend fun cancelOrderTinkoff(order: TinkoffOrder) {
        val operation = if (order.operation == OperationType.BUY) "ПОКУПКА!" else "ПРОДАЖА!"
        try {
            ordersService.cancel(order.orderId, portfolioManager.getActiveBrokerAccountId())
            Utils.showToastAlert("ТИ ${order.stock?.ticker} ордер отменён: $operation")
        } catch (e: Exception) { // возможно уже отменена
            Utils.showToastAlert("ТИ ${order.stock?.ticker} ордер УЖЕ отменён: $operation")
        }
    }

    suspend fun cancelOrderAlor(order: AlorOrder) {
        val operation = if (order.side == OperationType.BUY) "ПОКУПКА!" else "ПРОДАЖА!"
        order.stock?.let {
            cancelOrderAlorForId(order.id, it, order.side)
        }
    }

    suspend fun cancelOrderAlorForId(orderId: String, stock: Stock, operationType: OperationType) {
        val operation = if (operationType == OperationType.BUY) "ПОКУПКА!" else "ПРОДАЖА!"
        try {
            Utils.showToastAlert("ALOR ${stock.ticker} ордер отменён: $operation")
            alorOrdersService.cancel(orderId, AlorExchange.SPBX)
        } catch (e: Exception) { // TODO: тут всегда эксепшин из-за разного формата ответа, а ретрофит понимает только json
            // возможно уже отменена
//                Utils.showToastAlert("${order.stock?.ticker} ордер УЖЕ отменён: $operation")
        }
    }

    /******************** cancel all orders *************************/
    suspend fun cancelAllOrdersTinkoff() {
        for (order in portfolioManager.orders) {
            try {
                cancelOrderTinkoff(order)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        portfolioManager.refreshOrders()
    }

    suspend fun cancelAllOrdersAlor() {
        GlobalScope.launch(Dispatchers.Main) {
            for (order in alorPortfolioManager.orders) {
                try {
                    cancelOrderAlor(order)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            alorPortfolioManager.refreshOrders()
        }
    }

    /******************** replace order *************************/
    suspend fun replaceOrder(from: BaseOrder, price: Double, operationType: OperationType, refresh: Boolean = false) {
        if (from is TinkoffOrder) {
            replaceOrderTinkoff(from, price, operationType)
            if (refresh) portfolioManager.refreshOrders()
        }

        if (from is AlorOrder) {
            replaceOrderAlor(from, price, operationType)
            if (refresh) alorPortfolioManager.refreshOrders()
        }
    }

    suspend fun replaceOrderTinkoff(from: TinkoffOrder, price: Double, operationType: OperationType) {
        cancelOrderTinkoff(from)

        from.stock?.let {
            placeOrderTinkoff(it, price, from.getLotsRequested() - from.getLotsExecuted(), operationType)
        }
    }

    private suspend fun replaceOrderAlor(from: AlorOrder, price: Double, operationType: OperationType) {
        cancelOrderAlor(from)

        from.stock?.let {
            placeOrderAlor(it, price, from.getLotsRequested() - from.getLotsExecuted(), operationType)
        }
    }

    /******************** order utils *************************/
    fun getOrderForId(orderId: String, operationType: OperationType): BaseOrder? {
        var order: BaseOrder? = null

        if (SettingsManager.getBrokerTinkoff()) {
            order = portfolioManager.getOrderForId(orderId, operationType)
        }

        if (SettingsManager.getBrokerAlor()) {
            order = alorPortfolioManager.getOrderForId(orderId, operationType)
        }

        return order
    }

    public fun getOrdersAllForStock(stock: Stock, operation: OperationType): List<BaseOrder> {
        val orders = mutableListOf<BaseOrder>()

        if (SettingsManager.getBrokerTinkoff()) {
            val tinkoff = portfolioManager.getOrderAllForStock(stock, operation)
            orders.addAll(tinkoff)
        }

        if (SettingsManager.getBrokerAlor()) {
            val alor = alorPortfolioManager.getOrderAllForStock(stock, operation)
            orders.addAll(alor)
        }

        return orders
    }

    public fun getOrdersAll(): MutableList<BaseOrder> {
        val orders = mutableListOf<BaseOrder>()

        if (SettingsManager.getBrokerTinkoff()) {
            val tinkoff = portfolioManager.orders
            orders.addAll(tinkoff)
        }

        if (SettingsManager.getBrokerAlor()) {
            val alor = alorPortfolioManager.orders
            orders.addAll(alor)
        }

        return orders
    }

    suspend fun refreshDeposit() {
        if (SettingsManager.getBrokerTinkoff()) {
            portfolioManager.refreshDeposit()
        }

        if (SettingsManager.getBrokerAlor()) {
            alorPortfolioManager.refreshDeposit()
        }
    }

    suspend fun refreshOrders() {
        if (SettingsManager.getBrokerTinkoff()) {
            portfolioManager.refreshOrders()
        }

        if (SettingsManager.getBrokerAlor()) {
            alorPortfolioManager.refreshOrders()
        }
    }

    /******************** position utils *************************/
    public fun getPositionsAll(): MutableList<BasePosition> {
        val orders = mutableListOf<BasePosition>()

        if (SettingsManager.getBrokerTinkoff()) {
            val tinkoff = portfolioManager.portfolioPositions
            orders.addAll(tinkoff)
        }

        if (SettingsManager.getBrokerAlor()) {
            val alor = alorPortfolioManager.portfolioPositions
            orders.addAll(alor)
        }

        return orders
    }
}