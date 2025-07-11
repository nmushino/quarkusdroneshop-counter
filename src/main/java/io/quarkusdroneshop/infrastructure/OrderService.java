package io.quarkusdroneshop.infrastructure;

import io.debezium.outbox.quarkus.ExportedEvent;
import io.quarkusdroneshop.counter.domain.Order;
import io.quarkusdroneshop.counter.domain.OrderRepository;
import io.quarkusdroneshop.counter.domain.OrderStatus;
import io.quarkusdroneshop.counter.domain.Item;
import io.quarkusdroneshop.counter.domain.commands.PlaceOrderCommand;
import io.quarkusdroneshop.counter.domain.valueobjects.DashboardUpdate;
import io.quarkusdroneshop.counter.domain.valueobjects.OrderEventResult;
import io.quarkusdroneshop.counter.domain.valueobjects.OrderTicket;
import io.quarkusdroneshop.counter.domain.valueobjects.OrderUpdate;
import io.quarkusdroneshop.counter.domain.valueobjects.TicketUp;
import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.NotFoundException;

@ApplicationScoped
public class OrderService {

    final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Inject
    ThreadContext threadContext;

    @Inject
    OrderRepository orderRepository;

    @Inject
    Event<ExportedEvent<?, ?>> event;

    @Channel("qdca10")
    Emitter<OrderTicket> qdca10Emitter;

    @Channel("qdca10pro")
    Emitter<OrderTicket> qdca10proEmitter;

    @Channel("web-updates")
    Emitter<DashboardUpdate> dashboardUpdateEmitter;

    // トランザクション内処理
    @Transactional
    public OrderEventResult onOrderInTx(final PlaceOrderCommand placeOrderCommand) {
        logger.debug("onOrderInTx: {}", placeOrderCommand);

        OrderEventResult result = Order.createFromCommand(placeOrderCommand);

        orderRepository.persist(result.getOrder());

        result.getOutboxEvents().forEach(exportedEvent -> {
            logger.debug("Firing event: {}", exportedEvent);
            event.fire(exportedEvent);
        });

        return result;
    }

    // トランザクション内処理（orders-up）
    @Transactional
    public OrderEventResult onOrderUpTx(final TicketUp ticketUp) {
        logger.debug("onOrderUpTx: {}", ticketUp);
        Order order = orderRepository.findById(ticketUp.getOrderId());

        if (order == null) {
            logger.error("Order not found for ID: {}", ticketUp.getOrderId());
            throw new NotFoundException("Order not found for ID: " + ticketUp.getOrderId());
        }

        OrderEventResult result = order.applyOrderTicketUp(ticketUp);

        result.getOutboxEvents().forEach(event::fire);

        return result;
    }

    // Kafka送信をトランザクション外で実行
    public void sendOrderUpdate(OrderUpdate update) {
        logger.debug("Sending DashboardUpdate: {}", update);
    
        DashboardUpdate dashboardUpdate = new DashboardUpdate(
            update.getOrderId(),
            update.getItemId(),
            update.getName(),
            update.getItem(),
            update.getStatus(),
            update.getMadeBy().orElse("unknown")  // Optional<String> なので orElse で補完
        );
        dashboardUpdateEmitter.send(dashboardUpdate);
    }

    public void sendQdca10(OrderTicket ticket) {
        logger.debug("Sending QDCA10 Ticket: {}", ticket);
        qdca10Emitter.send(ticket);
    }

    public void sendQdca10pro(OrderTicket ticket) {
        logger.debug("Sending QDCA10Pro Ticket: {}", ticket);
        qdca10proEmitter.send(ticket);
    }

    @Override
    public String toString() {
        return "OrderService{" +
                "threadContext=" + threadContext +
                ", orderRepository=" + orderRepository +
                ", event=" + event +
                ", qdca10Emitter=" + qdca10Emitter +
                ", qdca10proEmitter=" + qdca10proEmitter +
                ", orderUpdateEmitter=" + dashboardUpdateEmitter +
                '}';
    }
}