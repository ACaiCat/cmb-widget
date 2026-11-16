import ink.terraria.cmbwidget.data.*;
import java.util.*;

public class BillMathChecks {
    static Bill bill(String kind,long minor,String currency) { return new Bill(1,"2026-11-16T12:00:00","示例店",currency,"1234",kind,minor); }
    static void expect(long actual,long expected) { if(actual!=expected) throw new AssertionError(actual+" != "+expected); }
    public static void main(String[] args) {
        var fullRefund=List.of(bill("EXPENSE",10000,"CNY"),bill("REFUND",10000,"CNY"));
        expect(BillMathKt.netSpending(fullRefund,"CNY"),0);
        expect(BillMathKt.balanceChange(fullRefund,"CNY"),0);
        var partialAndIncome=List.of(bill("EXPENSE",10000,"CNY"),bill("REFUND",2500,"CNY"),bill("INCOME",50000,"CNY"));
        expect(BillMathKt.netSpending(partialAndIncome,"CNY"),7500);
        expect(BillMathKt.balanceChange(partialAndIncome,"CNY"),42500);
        var currencies=List.of(bill("EXPENSE",10000,"CNY"),bill("REFUND",50000,"USD"),bill("TRANSFER",99999,"CNY"));
        expect(BillMathKt.netSpending(currencies,"CNY"),10000);
        expect(BillMathKt.netSpending(currencies,"USD"),-50000);
        if(!BillMathKt.signedMoney(-9717,"CNY").equals("−¥97.17")) throw new AssertionError("expense sign");
        if(!BillMathKt.signedMoney(2500,"CNY").equals("+¥25.00")) throw new AssertionError("refund sign");
        if(!BillMathKt.signedMoney(0,"CNY").equals("¥0.00")) throw new AssertionError("zero sign");
        System.out.println("Refund cancellation, partial refunds, unrelated income, currency isolation and signed totals passed");
    }
}
