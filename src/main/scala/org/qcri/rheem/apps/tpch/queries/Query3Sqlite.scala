package org.qcri.rheem.apps.tpch.queries

import org.qcri.rheem.api._
import org.qcri.rheem.apps.tpch.CsvUtils
import org.qcri.rheem.apps.tpch.data.{Customer, LineItem, Order}
import org.qcri.rheem.core.api.{Configuration, RheemContext}
import org.qcri.rheem.core.plugin.Plugin
import org.qcri.rheem.sqlite3.operators.Sqlite3TableSource

/**
  * Rheem implementation of TPC-H Query 3.
  *
  * {{{
  * select
  *   l_orderkey,
  *   sum(l_extendedprice*(1-l_discount)) as revenue,
  *   o_orderdate,
  *   o_shippriority
  * from
  *   customer,
  *   orders,
  *   lineitem
  * where
  *   c_mktsegment = '[SEGMENT]'
  *   and c_custkey = o_custkey
  *   and l_orderkey = o_orderkey
  *   and o_orderdate < date '[DATE]'
  *   and l_shipdate > date '[DATE]'
  * group by
  *   l_orderkey,
  *   o_orderdate,
  *   o_shippriority
  * order by
  *   revenue desc,
  *   o_orderdate;
  * }}}
  */
class Query3Sqlite(plugins: Plugin*) {

  def apply(configuration: Configuration,
            segment: String = "BUILDING",
            date: String = "1995-03-15") = {

    val rheemCtx = new RheemContext(configuration)
    plugins.foreach(rheemCtx.register)

    // Read, filter, and project the customer data.
    val _segment = segment
    val customerKeys = rheemCtx
      .readTable(new Sqlite3TableSource("CUSTOMER", Customer.fields: _*))
      .withName("Load CUSTOMER table")

      .filter(_.getString(6) == _segment, sqlUdf = s"c_mktsegment LIKE '$segment%'", selectivity = .25)
      .withName("Filter customers")

      .projectRecords(Seq("c_custkey"))
      .withName("Project customers")

      .map(_.getLong(0))
      .withName("Extract customer ID")

    // Read, filter, and project the order data.
    val _date = CsvUtils.parseDate(date)
    val orders = rheemCtx
      .load(new Sqlite3TableSource("ORDERS", Order.fields: _*))
      .withName("Load ORDERS table")

      .filter(t => CsvUtils.parseDate(t.getString(4)) > _date, sqlUdf = s"o_orderdate < date('$date')")
      .withName("Filter orders")

      .projectRecords(Seq("o_orderkey", "o_custkey", "o_orderdate", "o_shippriority"))
      .withName("Project orders")

      .map(order => (order.getLong(0), // orderKey
        order.getLong(1), // custKey
        CsvUtils.parseDate(order.getString(2)), // orderDate
        order.getInt(3)) // shipPriority
      )
      .withName("Unpack orders")

    // Read, filter, and project the line item data.
    val lineItems = rheemCtx
      .readTable(new Sqlite3TableSource("LINEITEM", LineItem.fields: _*))
      .withName("Load LINEITEM table")

      .filter(t => CsvUtils.parseDate(t.getString(10)) > _date, sqlUdf = s"l_shipDate > date('$date')")
      .withName("Filter line items")

      .projectRecords(Seq("l_orderkey", "l_extendedprice", "l_discount"))
      .withName("Project line items")

      .map(li => (
        li.getLong(0), //li.orderKey,
        li.getDouble(1) * (1 - li.getDouble(2)) //li.extendedPrice * (1 - li.discount)
        ))
      .withName("Extract line item data")

    // Join and aggregate the different datasets.
    customerKeys
      .join[(Long, Long, Int, Int), Long](identity, orders, _._2)
      .withName("Join customers with orders")
      .map(_.field1) // (orderKey, custKey, orderDate, shipPriority)
      .withName("Project customer-order join product")

      .join[(Long, Double), Long](_._1, lineItems, _._1)
      .withName("Join CO with line items")
      .map(coli => Query3Result(
        orderKey = coli.field1._1,
        revenue = coli.field1._2,
        orderDate = coli.field0._3,
        shipPriority = coli.field0._4
      ))
      .withName("Project CO-line-item join product")

      .reduceByKey(
        t => (t.orderKey, t.orderDate, t.shipPriority),
        (t1, t2) => {
          t1.revenue += t2.revenue;
          t2
        }
      )
      .withName("Aggregate revenue")
      .withUdfJarsOf(classOf[Query3Sqlite])
      .collect(s"TPC-H (${this.getClass.getSimpleName})")
  }

}
