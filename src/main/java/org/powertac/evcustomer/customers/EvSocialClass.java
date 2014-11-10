/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package org.powertac.evcustomer.customers;

import org.apache.log4j.Logger;
import org.powertac.common.*;
import org.powertac.common.config.ConfigurableInstance;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.interfaces.CustomerModelAccessor;
import org.powertac.common.interfaces.TariffMarket;
import org.powertac.common.state.Domain;
import org.powertac.customer.AbstractCustomer;
import org.powertac.evcustomer.Config;
import org.powertac.evcustomer.beans.*;

import java.util.*;
import java.util.Map.Entry;

/**
 * @author Konstantina Valogianni, Govert Buijs
 */
@Domain
@ConfigurableInstance
public class EvSocialClass extends AbstractCustomer
{
  private static Logger log = Logger.getLogger(EvSocialClass.class.getName());

  private RandomSeed generator;
  
  private Config config;

  private Map<CustomerInfo, TariffEvaluator> tariffEvaluators;

  private ArrayList<EvCustomer> evCustomers;

  // ignore quantities less than epsilon
  private double epsilon = 1e-6;

  private double consumptionLoad = 0.0;
  private double evLoad = 0.0;
  private double upRegulation = 0.0;
  private double downRegulation = 0.0;

  public EvSocialClass (String name)
  {
    super(name);

    Comparator<CustomerInfo> comp = new Comparator<CustomerInfo>()
    {
      @Override
      public int compare (CustomerInfo customer1, CustomerInfo customer2)
      {
        return customer1.getName().compareToIgnoreCase(customer2.getName());
      }
    };
    tariffEvaluators = new TreeMap<CustomerInfo, TariffEvaluator>(comp);
  }

  public void initialize (Map<Integer, SocialGroup> groups,
                          Map<Integer, SocialGroupDetail> groupDetails,
                          Map<Integer, Activity> activities,
                          Map<Integer, Map<Integer, ActivityDetail>> allActivityDetails,
                          List<CarType> carTypes,
                          int populationCount,
                          int seed)
  {
    this.generator = service.getRandomSeedRepo().
        getRandomSeed("EvSocialClass", seed, "initialize");
    
    config = Config.getInstance();

    evCustomers = new ArrayList<EvCustomer>();

    // TODO - why do we have to look them up?
    List<CustomerInfo> customerInfos1 = 
        service.getCustomerRepo()
        .findByName(createInfoName(PowerType.CONSUMPTION));
    List<CustomerInfo> customerInfos2 = 
        service.getCustomerRepo()
        .findByName(createInfoName(PowerType.ELECTRIC_VEHICLE));

    for (int i = 0; i < populationCount; i++) {
      int randomGroupId = getRandomGroupId(groupDetails, generator);

      SocialGroup group = groups.get(randomGroupId);
      SocialGroupDetail groupDetail = groupDetails.get(randomGroupId);
      Map<Integer, ActivityDetail> activityDetails =
          allActivityDetails.get(randomGroupId);

      String gender = "female";
      if (generator.nextDouble() < groupDetail.getMaleProbability()) {
        gender = "male";
      }

      // For now, all carTypes have equal probability
      int randomCar = generator.nextInt(carTypes.size());
      CarType carType = carTypes.get(randomCar);

      EvCustomer evCustomer = new EvCustomer();
      evCustomer.initialize(
          group, gender, activities, activityDetails, carType, generator);
      evCustomers.add(evCustomer);

      if (customerInfos1.size() > 0) {
        CustomerInfo customer = customerInfos1.get(0);
        double weight = generator.nextDouble() * config.getWeightInconvenience();
        double weeks = config.getMinDefaultDuration() +
            generator.nextInt(config.getMaxDefaultDuration() -
                              config.getMinDefaultDuration());
        tariffEvaluators.put(customer,
            createTariffEvaluator(customer, weight, weeks));
      }

      if (customerInfos2.size() > 0) {
        CustomerInfo customer = customerInfos2.get(0);
        double weight = generator.nextDouble() * config.getWeightInconvenience();
        double expDuration = config.getMinDefaultDuration() +
            generator.nextInt(config.getMaxDefaultDuration() -
                              config.getMinDefaultDuration());
        tariffEvaluators.put(customer,
            createTariffEvaluator(customer, weight, expDuration));
      }
    }
  }

  protected TariffEvaluator createTariffEvaluator (CustomerInfo customerInfo,
                                                   double weight,
                                                   double expDuration)
  {
    TariffEvaluationWrapper wrapper =
        new TariffEvaluationWrapper(customerInfo, evCustomers,
                                    generator, config);
    TariffEvaluator te = new TariffEvaluator(wrapper);
    te.initializeInconvenienceFactors(config.getTouFactor(),
        config.getTieredRateFactor(),
        config.getVariablePricingFactor(),
        config.getInterruptibilityFactor());
    te.withInconvenienceWeight(weight)
        .withInertia(config.getNsInertia())
        .withPreferredContractDuration(expDuration)
        .withRationality(config.getRationalityFactor())
        .withTariffEvalDepth(config.getTariffCount())
        .withTariffSwitchFactor(config.getBrokerSwitchFactor());
    return te;
  }

  protected int getRandomGroupId (Map<Integer, SocialGroupDetail> groupDetails,
                                  Random gen)
  {
    double r = gen.nextDouble();
    for (Entry<Integer, SocialGroupDetail> entry : groupDetails.entrySet()) {
      r -= entry.getValue().getProbability();
      if (r < 0) {
        return entry.getKey();
      }
    }

    return 1;
  }

  // =====SUBSCRIPTION FUNCTIONS===== //

  public void subscribeDefault (TariffMarket tariffMarketService)
  {
    for (CustomerInfo customer : getCustomerInfos()) {
      Tariff candidate =
          tariffMarketService.getDefaultTariff(customer.getPowerType());
      if (null == candidate) {
        log.error("No default tariff for " + customer.getPowerType().toString());
      }
      else {
        log.info("Subscribe " + customer.getName()
                 + " to " + candidate.getPowerType().toString());
      }
      tariffMarketService.subscribeToTariff(candidate, customer,
                                            customer.getPopulation());
    }
  }

  // =====CONSUMPTION FUNCTIONS===== //
  public void consumePower ()
  {
    for (CustomerInfo customer : getCustomerInfos()) {
      List<TariffSubscription> subs = getCurrentSubscriptions(customer);

      if (subs != null && subs.size() > 0) {
        TariffSubscription sub = subs.get(0);

        if (customer.getPowerType() == PowerType.CONSUMPTION) {
          sub.usePower(consumptionLoad);
          log.debug("Consumption Load for Customer " + customer.toString()
              + ": " + consumptionLoad + " for subscriptions " + sub.toString());
        }
        else if (customer.getPowerType() == PowerType.ELECTRIC_VEHICLE) {
          sub.usePower(evLoad);
          log.debug("Electric Vehicule Load for Customer " + customer.toString()
              + ": " + evLoad + " for subscriptions " + sub.toString());
        }
      }
    }
  }

  // =====EVALUATION FUNCTIONS===== //

  /**
   * This is the basic evaluation function, taking into consideration the
   * minimum cost without shifting the appliances' load but the tariff chosen
   * is picked up randomly by using a possibility pattern. The better tariffs
   * have more chances to be chosen.
   */
  @Override
  public void evaluateTariffs (List<Tariff> tariffs)
  {
    for (CustomerInfo customer : getCustomerInfos()) {
      log.info(name + ": evaluate tariffs for " + customer.getName());
      TariffEvaluator evaluator = tariffEvaluators.get(customer);
      evaluator.evaluateTariffs();
    }
  }

  // =====STEP FUNCTIONS===== //

  @Override
  public void step ()
  {
    Timeslot ts = service.getTimeslotRepo().currentTimeslot();
    int hour = ts.getStartTime().getHourOfDay();
    int day = ts.getStartTime().getDayOfWeek();

    // Always do handleRegulations first
    handleRegulations(day, hour);
    makeDayPlanning(hour, day);
    doActivities(day, hour);
    getLoads(day, hour);
    setRegulations();
    consumePower();
  }

  protected void doActivities (int day, int hour)
  {
    for (EvCustomer evCustomer : evCustomers) {
      evCustomer.doActivities(day, hour);
    }
  }

  private void makeDayPlanning (int hour, int day)
  {
    if (hour != 0) {
      return;
    }

    for (EvCustomer evCustomer : evCustomers) {
      evCustomer.makeDayPlanning(day);
    }
  }

  /*
   * When getting the load for consumePower, the batteries are charged according
   * to the desired capacity. But in reality the capacity might be regulated.
   */
  private void handleRegulations (int day, int hour)
  {
    for (CustomerInfo customer : getCustomerInfos()) {
      if (customer.getPowerType() != PowerType.ELECTRIC_VEHICLE) {
        continue;
      }

      List<TariffSubscription> subs = getCurrentSubscriptions(customer);
      if (subs != null && subs.size() > 0) {
        double actualRegulation =
            subs.get(0).getRegulation() * customer.getPopulation();

        log.info(name + " regulate : " + actualRegulation);

        double regulationFactor;
        if (actualRegulation > epsilon && upRegulation > epsilon) {
          regulationFactor = actualRegulation / upRegulation;
        }
        else if (actualRegulation < -epsilon && downRegulation < -epsilon) {
          regulationFactor = -1 * actualRegulation / downRegulation;
        }
        else {
          return;
        }

        // Communicate percentage up- and down-regulation to the ev customers
        for (EvCustomer evCustomer : evCustomers) {
          evCustomer.regulate(hour, regulationFactor);
        }
      }
    }
  }

  private void setRegulations ()
  {
    for (CustomerInfo customer : getCustomerInfos()) {
      if (customer.getPowerType() != PowerType.ELECTRIC_VEHICLE) {
        continue;
      }

      List<TariffSubscription> subs = getCurrentSubscriptions(customer);
      if (subs != null && subs.size() > 0) {
        RegulationCapacity regulationCapacity = new RegulationCapacity(
            upRegulation / customer.getPopulation(),
            downRegulation / customer.getPopulation());
        subs.get(0).setRegulationCapacity(regulationCapacity);

        log.info(name + " setting regulation for " +
            subs.get(0).getCustomer().getName() + " up : " + upRegulation +
            " ; down : " + downRegulation);
      }
    }
  }

  protected void getLoads (int day, int hour)
  {
    consumptionLoad = 0.0;
    evLoad = 0.0;
    upRegulation = 0.0;
    downRegulation = 0.0;

    for (EvCustomer evCustomer : evCustomers) {
      double[] loads = evCustomer.getLoads(day, hour);

      consumptionLoad += loads[0];
      evLoad += loads[1];
      upRegulation += loads[2];
      downRegulation += loads[3];
    }

    log.info(String.format("%s : consumption = % 7.2f ; electric vehicule = " +
            "% 7.2f ; up regulation = % 7.2f ; down regulation = % 7.2f",
        name, consumptionLoad, evLoad, upRegulation, downRegulation
    ));
  }

  public void addCustomer (int populationCount, List<CarType> carTypes,
                           PowerType powerType)
  {
    String infoName = createInfoName(powerType);
    CustomerInfo customerInfo =
        new CustomerInfo(infoName, populationCount).withPowerType(powerType);

    if (powerType == PowerType.ELECTRIC_VEHICLE) {
      double storageCapacity = 0;
      double chargingCapacity = 0;

      for (CarType carType : carTypes) {
        storageCapacity = Math.max(storageCapacity, carType.getMaxCapacity());
        chargingCapacity = Math.max(chargingCapacity, carType.getHomeCharging());
      }

      customerInfo = customerInfo
              .withControllableKW(-chargingCapacity)
              .withUpRegulationKW(-chargingCapacity)
              .withDownRegulationKW(chargingCapacity)
              .withStorageCapacity(storageCapacity)
              .withMultiContracting(true);
    }

    addCustomerInfo(customerInfo);
    service.getCustomerRepo().add(customerInfo);
  }

  public String createInfoName (PowerType type)
  {
    String s = type.toString().replace("_", " ");

    final StringBuilder result = new StringBuilder(name.length() + s.length() + 1);
    result.append(name).append(" ");

    String[] words = s.split("\\s");
    for (int i = 0, l = words.length; i < l; ++i) {
      if (i > 0) {
        result.append(" ");
      }
      result.append(Character.toUpperCase(words[i].charAt(0)))
          .append(words[i].substring(1).toLowerCase());
    }

    return result.toString();
  }

  @Override
  public String toString ()
  {
    return name;
  }

  /**
   * Returns the current tariff subscriptions for this customerInfo
   */
  public List<TariffSubscription> getCurrentSubscriptions (CustomerInfo cust)
  {
    List<TariffSubscription> subs =
        service.getTariffSubscriptionRepo()
        .findActiveSubscriptionsForCustomer(cust);
    if (subs.size() > 1) {
      log.warn("Multiple subscriptions " + subs.size() + " for " + name);
    }
    return subs;
  }

  // ===== USED FOR TESTING ===== //

  public ArrayList<EvCustomer> getEvCustomers ()
  {
    return evCustomers;
  }

  public Random getGenerator ()
  {
    return generator;
  }
}

class TariffEvaluationWrapper implements CustomerModelAccessor
{
  private CustomerInfo customerInfo;
  private ArrayList<EvCustomer> evCustomers;
  private Random generator;
  private Config config;
  
  private final static int hrsPerDay = 24;

  public TariffEvaluationWrapper (CustomerInfo customerInfo,
                                  ArrayList<EvCustomer> evCustomers,
                                  Random generator,
                                  Config config)
  {
    this.customerInfo = customerInfo;
    this.evCustomers = evCustomers;
    this.generator = generator;
    this.config = config;
  }

  @Override
  public CustomerInfo getCustomerInfo ()
  {
    return customerInfo;
  }

  /**
   * TODO: this does not appear to be a reasonable profile
   */
  @Override
  public double[] getCapacityProfile (Tariff tariff)
  {
    double[] result = new double[config.getProfileLength()];

    for (int i = 0; i < hrsPerDay; i++) {
      for (EvCustomer evCustomer : evCustomers) {
        result[i] += evCustomer.getDominantLoad() / hrsPerDay;
      }
      result[i] /= evCustomers.size();
    }

    return result;
  }

  @Override
  public double getBrokerSwitchFactor (boolean isSuperseding)
  {
    double result = config.getBrokerSwitchFactor();
    if (isSuperseding) {
      return result * 5.0;
    }
    return result;
  }

  @Override
  public double getTariffChoiceSample ()
  {
    return generator.nextDouble();
  }

  @Override
  public double getInertiaSample ()
  {
    return generator.nextDouble();
  }
}