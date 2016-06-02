package org.shop.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.shop.dao.CartRepository;
import org.shop.model.*;
import org.shop.spring.aop.ProfileExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Created by vprasanna on 5/30/2016.
 */
@Service
public class CartService {

    private static final Log logger = LogFactory.getLog(CartService.class);
    private static final double SHIPPING = 0.05;
    private static final double HANDLING_CHARGES = 0.03;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private UserServices userServices;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private CounterService counter;

    @ProfileExecution
    public Cart getMyCart() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.setKeepTaskList(true);
        stopWatch.start("Get Cart: Get User");
        User currentUser = verifyAndGetUser();
        stopWatch.stop();

        stopWatch.start("Get Cart: Find User");
        Cart cart = cartRepository.findByUsername(currentUser.getUsername());
        stopWatch.stop();

        if (null == cart) {
            logger.info("Crating new empty cart");
            stopWatch.start("Get Cart: Create New Cart");
            cart = new Cart();
            cart.setUsername(currentUser.getUsername());
            //Saving to DB to get a new Cart ID
            cart = cartRepository.save(cart);
            stopWatch.stop();
        }
        logger.info("Cart Performance:" + stopWatch.prettyPrint());
        return cart;
    }


    public Cart addItemToCart(String productId, int quantity) {

        if (StringUtils.isEmpty(productId) || quantity <= 0) {
            throw new IllegalArgumentException("Product ID cannot be empty or quantity cannot be 0 or less whle adding item to cart.");
        }

        Cart cart = getMyCart();

        List<LineItem> lineItems = cart.getLineItems();
        Optional<LineItem> item = lineItems.stream()
                .filter(lineItem -> productId.equals(lineItem.getProductId()))
                .findFirst();

        LineItem lineItem = null;

        if (item.isPresent()) {
            lineItem = item.get();
            lineItems.remove(item);
        } else {
            lineItem = createLineItem(productId, quantity);
        }

        if (null == lineItem) {
            throw new IllegalStateException("Line Item cannot be null or empty");
        }

        lineItem.setQuantity(lineItem.getQuantity() + quantity);
        calculateTax(productId, lineItem);

        lineItems.add(lineItem);

        counter.init();
        lineItems.stream().forEach(line ->
                line.setId(String.valueOf(counter.next())));
        counter.reset();

        cart.setLineItems(lineItems);

        Summary summary = creatSummary(cart);
        cart.setSummary(summary);
        cart = cartRepository.save(cart);

        return cart;
    }

    private void calculateTax(String productId, LineItem lineItem) {
        Product product = inventoryService.findById(productId);
        lineItem.setTax(lineItem.getPrice() * product.getPricingInfo().getTaxPercentage() / 100);
    }

    private Summary creatSummary(Cart cart) {
        Summary summary = new Summary();

        double shipping = getShippingTotal(cart);
        double tax = getTaxTotal(cart);
        double handlingCharges = getHandlingCharges(cart);
        Currency currency = getItemCurrency(cart);
        double netTotal = getNetTotal(cart);
        double grossTotal = netTotal + shipping + handlingCharges + tax;

        summary.setTax(tax);
        summary.setCurrency(currency);
        summary.setNetTotal(netTotal);
        summary.setGrossTotal(grossTotal);
        summary.setHandlingCharges(handlingCharges);
        summary.setShipping(shipping);


        return summary;
    }

    private double getNetTotal(Cart cart) {
        if (null == cart) {
            throw new IllegalArgumentException("Cart is null");
        }
        return cart.getLineItems().stream()
                .mapToDouble(LineItem::getPrice)
                .sum();
    }

    private double getHandlingCharges(Cart cart) {
        if (null == cart) {
            throw new IllegalArgumentException("Cart is null");
        }
        //Currently 3% of net
        return getNetTotal(cart) * HANDLING_CHARGES;
    }

    private Currency getItemCurrency(Cart cart) {
        return Currency.INR;
    }

    private double getTaxTotal(Cart cart) {
        if (null == cart) {
            throw new IllegalArgumentException("Cart is null");
        }
        return cart.getLineItems().stream()
                .mapToDouble(LineItem::getTax)
                .sum();
    }

    private double getShippingTotal(Cart cart) {
        if (null == cart) {
            throw new IllegalArgumentException("Cart is null");
        }
        //Currently 5% of net
        return getNetTotal(cart) * SHIPPING;
    }

    public LineItem createLineItem(String productId, int quantity) {
        Product product = inventoryService.findById(productId);
        LineItem lineItem = new LineItem();
        lineItem.setProductId(productId);
        lineItem.setQuantity(quantity);
        lineItem.setCurrency(product.getPricingInfo().getCurrency());
        lineItem.setDescription(product.getName());
        lineItem.setPrice(product.getPricingInfo().getBasePrice() * quantity);
        lineItem.setImageURL(product.getImageURL());
        return lineItem;
    }

    public User verifyAndGetUser() {
        User currentUser = userServices.getAuthenticatedUser();
        if (null == currentUser) {
            throw new IllegalStateException("Cannot View/Modify cart for non logged in user.");
        }
        return currentUser;
    }
}